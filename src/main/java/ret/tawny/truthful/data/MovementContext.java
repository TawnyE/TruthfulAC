package ret.tawny.truthful.data;

public final class MovementContext {
    private boolean liquid;
    private boolean climbable;
    private boolean web;
    private boolean powderSnow;
    private boolean slimeBounce;
    private boolean honey;

    public void update(PlayerData data) {
        this.liquid = data.isInLiquid();
        this.climbable = data.isOnClimbable();
        this.web = data.isInWeb();
        this.powderSnow = data.isExempt(ExemptionType.POWDER_SNOW);
        this.slimeBounce = data.getTicksTracked() - data.getLastSlimeTick() < 8;
        this.honey = data.getTicksTracked() - data.getLastHoneyTick() < 8;
    }

    public boolean isLiquid() { return liquid; }
    public boolean isClimbable() { return climbable; }
    public boolean isWeb() { return web; }
    public boolean isPowderSnow() { return powderSnow; }
    public boolean isSlimeBounce() { return slimeBounce; }
    public boolean isHoney() { return honey; }

    public boolean isEnvironmentUnsafeForPrediction() {
        return liquid || climbable || web || powderSnow || slimeBounce || honey;
    }
}
