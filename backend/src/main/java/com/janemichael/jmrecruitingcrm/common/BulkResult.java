package com.janemichael.jmrecruitingcrm.common;

import java.util.List;

/**
 * What a bulk action on a list did: how many records changed, which were left alone and why,
 * and the number to pass to Undo (null when nothing changed, or after an Undo).
 */
public record BulkResult(int changed, List<Skipped> skipped, Long undoId) {

    /** Most records one bulk action takes, so a request can't hold the database for long. */
    public static final int MAX_IDS = 2000;

    public record Skipped(Long id, String name, String reason) {
    }
}
