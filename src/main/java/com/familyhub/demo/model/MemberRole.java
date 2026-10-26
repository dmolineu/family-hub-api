package com.familyhub.demo.model;

/**
 * Whether a family member is a parent or a kid. Parents can hold a PIN and approve
 * chores; a kid's completed chores wait for a parent before their points count.
 */
public enum MemberRole {
    PARENT,
    KID
}
