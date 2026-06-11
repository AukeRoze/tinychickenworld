package com.youtubeauto.videogen.cost;

import java.util.concurrent.atomic.DoubleAdder;

/**
 * Per-job mutable cost accumulator. Cap is set at construction.
 */
public class CostBudget {

    private final double capEur;
    private final DoubleAdder spentEur = new DoubleAdder();

    public CostBudget(double capEur) {
        this.capEur = capEur;
    }

    public void add(double eur) { spentEur.add(eur); }

    public double spent() { return spentEur.sum(); }

    public double cap()   { return capEur; }

    public double remaining() { return Math.max(0.0, capEur - spent()); }

    /** True once cumulative spend crosses 80% of the cap. */
    public boolean nearby() { return spent() >= 0.8 * capEur; }

    /** True once cumulative spend exceeds the cap. */
    public boolean exceeded() { return spent() >= capEur; }
}
