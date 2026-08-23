package com.alsayed.financial_advisor_ai.chunking;

/**
 * Represents a text element with spatial coordinates for figure parsing.
 * Used by SpatialFigureParser to reconstruct relationships from layout.
 */
public class TextElement {
    private final String text;
    private final double x0;
    private final double y0;
    private final double x1;
    private final double y1;
    private final int pageNumber;

    public TextElement(String text, double x0, double y0, double x1, double y1, int pageNumber) {
        this.text = text;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.pageNumber = pageNumber;
    }

    public String getText() {
        return text;
    }

    public double getX0() {
        return x0;
    }

    public double getY0() {
        return y0;
    }

    public double getX1() {
        return x1;
    }

    public double getY1() {
        return y1;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public double getCenterX() {
        return (x0 + x1) / 2.0;
    }

    public double getCenterY() {
        return (y0 + y1) / 2.0;
    }

    public double getWidth() {
        return x1 - x0;
    }

    public double getHeight() {
        return y0 - y1; // BOTTOMLEFT origin: y0 > y1
    }

    /**
     * Calculate horizontal distance to another element.
     */
    public double horizontalDistanceTo(TextElement other) {
        return Math.abs(this.getCenterX() - other.getCenterX());
    }

    /**
     * Calculate vertical distance to another element.
     */
    public double verticalDistanceTo(TextElement other) {
        return Math.abs(this.getCenterY() - other.getCenterY());
    }

    /**
     * Calculate Euclidean distance to another element.
     */
    public double distanceTo(TextElement other) {
        double dx = this.horizontalDistanceTo(other);
        double dy = this.verticalDistanceTo(other);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Check if this element is in the same row as another element (within tolerance).
     */
    public boolean isSameRow(TextElement other, double tolerance) {
        return this.verticalDistanceTo(other) < tolerance;
    }

    /**
     * Check if this element is in the same column as another element (within tolerance).
     */
    public boolean isSameColumn(TextElement other, double tolerance) {
        return this.horizontalDistanceTo(other) < tolerance;
    }

    @Override
    public String toString() {
        return String.format("TextElement{text='%s', x0=%.1f, y0=%.1f, x1=%.1f, y1=%.1f, page=%d}",
                text, x0, y0, x1, y1, pageNumber);
    }
}
