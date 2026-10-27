package ret.tawny.truthful.predictionengine;

import ret.tawny.truthful.data.PlayerData;

/**
 * PredictionResult - output of one engine pass. Shared by every motion
 * variant (on-foot, elytra, liquid, vehicle) so the check layer speaks one
 * language regardless of which environment produced the numbers.
 */
public final class PredictionResult {
    public double offsetXZ = Double.MAX_VALUE;   // min horizontal deviation across all candidates
    public double offsetY = Double.MAX_VALUE;    // min vertical deviation across all candidates
    public double offset = Double.MAX_VALUE;     // combined deviation
    public String best = "none";                 // which candidate won (debug)
    public boolean hardViolation;                // nothing legal explains the motion
    public double slowMult = 1.0D;               // total slowdown multiplier in effect this tick
    public boolean sprintBackwards;               // claimed sprint while moving backwards/sideways
    public boolean knownInput;                    // exact client input was known this tick
    // Winning candidate's resolved motion (diagnostics: pred vs actual).
    public double predX, predY, predZ;
    // Carried (post end-of-tick) velocity the engine stored for next tick.
    public double carriedX, carriedY, carriedZ;
    // Physics diagnostics.
    public double friction = 0.91D;
    public double rawFriction = 0.6D;
    public int candidateCount;
    public String inputTag = "n/a";
    /** True when the engine produced a carried velocity the dispatcher should commit. */
    public boolean stateCarried;
    /** Named uncertainty sources active this tick, e.g. "piston=0.05;web=0.10". */
    public String allowanceDebug = "";

    public static PredictionResult unsupported() {
        PredictionResult r = new PredictionResult();
        r.offset = 0.0D;
        r.offsetXZ = 0.0D;
        r.offsetY = 0.0D;
        r.best = "unsupported";
        return r;
    }
}
