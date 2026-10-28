package com.familyhub.demo.sports;

import java.util.List;

/**
 * What the live panel shows for one game: each side's line, the situation right now and
 * the game's leaders. Read from the provider only while someone has the panel open.
 *
 * @param lines     away side first, as scores are read out
 * @param situation short lines such as "2-1, 1 out" or "Runners on 1st and 3rd"
 */
public record GameSummary(String gameId, String detail, List<TeamLine> lines, List<String> situation,
                          List<Leader> leaders) {

    /** A side's box-score line, for example R, H and E in baseball. */
    public record TeamLine(String abbreviation, List<Stat> stats) {
    }

    /** One labelled number in a line. */
    public record Stat(String label, String value) {
    }

    /** One of the game's leaders, for example ("Batting", "Eve Slugger", "2-3 | HR, 2 RBI"). */
    public record Leader(String label, String name, String line) {
    }
}
