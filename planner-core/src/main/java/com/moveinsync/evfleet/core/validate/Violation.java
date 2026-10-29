package com.moveinsync.evfleet.core.validate;

/** One broken rule. {@code subject} is the cab, hub or trip it concerns. */
public record Violation(Rule rule, String subject, String detail) {
}
