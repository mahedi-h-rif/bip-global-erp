package com.fatoora.api.invoice;

/** When an issued document goes to ZATCA. */
public enum SendMode {
    /** Submit right after signing. */
    NOW,
    /** Simplified only: queue and submit automatically before the 24-hour reporting limit. */
    LATER
}
