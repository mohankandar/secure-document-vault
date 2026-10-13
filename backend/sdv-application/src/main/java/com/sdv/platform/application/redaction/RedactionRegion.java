package com.sdv.platform.application.redaction;

public record RedactionRegion(int pageNumber, double x, double y, double width, double height) {
    public RedactionRegion {
        if (pageNumber < 1) throw new IllegalArgumentException("Redaction pageNumber must be at least 1.");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Redaction region coordinates must be finite.");
        }
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || x > 1 || y > 1 || width > 1 || height > 1
                || x + width > 1.0000001d || y + height > 1.0000001d) {
            throw new IllegalArgumentException("Redaction regions must fit within normalized page bounds.");
        }
        if (width < 0.001d || height < 0.001d) {
            throw new IllegalArgumentException("Redaction region is too small.");
        }
    }
}
