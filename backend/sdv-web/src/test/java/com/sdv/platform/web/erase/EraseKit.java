package com.sdv.platform.web.erase;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlers;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseReplay;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.file.FileEraseLogStore;
import com.documentplatform.eraselog.jdbc.PostgresErasePosition;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.persistence.fields.PlatformRecordValues;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.redaction.JdbcDocumentRedactionRepository;
import com.sdv.platform.persistence.redaction.JdbcHeldRedactionRepository;
import com.sdv.platform.persistence.retention.JdbcEraseRepository;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Task 0080: Vero's erasing as in the app, for tests against PostgreSQL: a real log, not keyed, one for one database. */
public final class EraseKit {
    public final JdbcEraseRepository erased;
    public final EraseGuard guard;
    public final EraseLog log;
    public final Map<String, EraseHandler> handlers = new LinkedHashMap<>();
    public final LoggedErasures port;
    public final EraseReplay replay;
    public final PlatformRecordValues values;

    public EraseKit(DataSource dataSource, PlatformTransactionManager tx, List<StorageProvider> storage) {
        this(dataSource, tx, storage, new PlatformRecordValues(dataSource, CustomerId.of(new UUID(0, 1)), Mockito.mock(KeyService.class)));
    }

    public EraseKit(DataSource dataSource, PlatformTransactionManager tx, List<StorageProvider> storage, PlatformRecordValues values) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.values = values;
        JdbcSecureIngestionRepository ingestion = new JdbcSecureIngestionRepository(jdbc);
        JdbcDocumentRedactionRepository redactions = new JdbcDocumentRedactionRepository(jdbc);
        JdbcHeldRedactionRepository held = new JdbcHeldRedactionRepository(jdbc);
        erased = new JdbcEraseRepository(jdbc);
        DocumentContent content = new DocumentContent(ingestion, redactions, erased, ingestion, storage, new JdbcFoundationRepository(jdbc), tx);
        EraseHandlersConfiguration made = new EraseHandlersConfiguration();
        for (EraseHandler h : List.of(made.documentErase(content, ingestion, erased), made.sealedOriginalErase(content, held, erased),
                made.replacedVersionErase(content, ingestion, redactions, erased), made.caseFieldsErase(values, content))) {
            handlers.put(h.kind(), h);
        }
        // A database belongs to one log: every test class on this database uses the same one.
        log = new EraseLog(new FileEraseLogStore(folder(jdbc.getJdbcTemplate())), Clock.systemUTC());
        PostgresErasePosition position = new PostgresErasePosition(dataSource);
        guard = new EraseGuard(log, position, Clock.systemUTC());
        guard.checkAtStartup();
        EraseHandlers all = EraseHandlers.of(handlers.values());
        port = new LoggedErasures(new Eraser(log, position, all, guard), tx);
        replay = new EraseReplay(log, position, all, Clock.systemUTC(), guard);
    }

    /** A new one for every scratch database. */
    public static Path folder(org.springframework.jdbc.core.JdbcTemplate db) {
        return Path.of("target", "erase-log-" + db.queryForObject("select oid::text from pg_database where datname=current_database()", String.class)).toAbsolutePath();
    }
}
