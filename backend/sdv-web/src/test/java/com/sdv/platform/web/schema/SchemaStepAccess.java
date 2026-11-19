package com.sdv.platform.web.schema;

/** Lets tests outside this package run the schema step's entry point without a database. */
public final class SchemaStepAccess {
    private SchemaStepAccess() { }

    public static int runWithoutArguments() {
        return SchemaStep.run(new String[]{}, k -> null, null);
    }
}
