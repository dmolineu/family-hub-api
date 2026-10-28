package com.familyhub.demo.sports;

/** The leagues a family can follow teams in. Men's college sports only, by the family's choice. */
public enum SportsLeague {
    NFL("NFL"),
    MLB("MLB"),
    COLLEGE_FOOTBALL("College football"),
    MENS_COLLEGE_BASKETBALL("Men's college basketball");

    private final String displayName;

    SportsLeague(String displayName) {
        this.displayName = displayName;
    }

    /** How the league is named to the family. */
    public String displayName() {
        return displayName;
    }
}
