package com.sdv.platform.persistence.fields;

import com.documentplatform.crypto.CustomerId;

import java.util.Collection;
import java.util.Map;

/**
 * Task 0078: many wrapped data keys opened in one key-service call, for a page of results. platform-crypto 1.0.0's KeyService opens one
 * per call. A key that can't be opened is left out. A failed call is an exception.
 */
public interface BatchUnwrap {
    Map<String, byte[]> unwrap(CustomerId customer, Collection<String> wrapped);
}
