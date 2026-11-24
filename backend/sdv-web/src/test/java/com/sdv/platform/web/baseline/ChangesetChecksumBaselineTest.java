package com.sdv.platform.web.baseline;

import liquibase.ChecksumVersion;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

/**
 * Task 0060 (plan 0059, 1.7): the checksum of every released changeset, as Liquibase 4.31.1 computes it and customers'
 * databases hold it. A Liquibase version that computes another would stop every schema step. A new changeset adds its
 * line at the end; a line that is there never changes.
 */
class ChangesetChecksumBaselineTest {
    @Test
    void everyReleasedChangesetKeepsItsChecksum() throws Exception {
        String master = "db/changelog/db.changelog-master.yaml";
        var accessor = new ClassLoaderResourceAccessor(getClass().getClassLoader());
        StringBuilder text = new StringBuilder();
        for (ChangeSet cs : ChangeLogParserFactory.getInstance().getParser(master, accessor)
                .parse(master, new ChangeLogParameters(), accessor).getChangeSets()) {
            text.append(cs.getId()).append(' ').append(cs.getAuthor()).append(' ').append(cs.generateCheckSum(ChecksumVersion.V9)).append('\n');
        }
        Baseline.matches("changeset-checksums", text);
    }
}
