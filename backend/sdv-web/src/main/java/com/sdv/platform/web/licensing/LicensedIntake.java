package com.sdv.platform.web.licensing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Task 0075: new work for Vero. Refused while the licence doesn't allow intake ({@link LicenceGate}). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LicensedIntake { }
