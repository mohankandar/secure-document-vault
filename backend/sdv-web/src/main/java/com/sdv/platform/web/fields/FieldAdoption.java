package com.sdv.platform.web.fields;

import com.sdv.platform.persistence.fields.PlatformFieldRepository;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Task 0077: the fields of a database of before become definitions, once, when Vero starts. A source that fails says so and stays refused. */
@Component
class FieldAdoption {
    private static final Logger log = LoggerFactory.getLogger(FieldAdoption.class);
    private final DatabaseSchemaCheck schema;
    private final PlatformFieldRepository fields;

    FieldAdoption(DatabaseSchemaCheck schema, PlatformFieldRepository fields) {
        this.schema = schema;
        this.fields = fields;
    }

    @EventListener(ApplicationReadyEvent.class)
    void adopt() {
        if (!schema.current()) return;
        for (var system : fields.systems()) {
            try {
                int taken = fields.adopt(system[0], system[1]);
                if (taken > 0) log.info("Case fields: {} taken over for sending system {}.", taken, system[1]);
            } catch (RuntimeException ex) {
                log.error("Case fields of sending system {} can't be taken over; its deliveries are refused until this is put right. Cause: {}", system[1], ex.getMessage());
            }
        }
    }
}
