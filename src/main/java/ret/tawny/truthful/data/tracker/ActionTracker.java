package ret.tawny.truthful.data.tracker;

import java.util.ArrayList;
import java.util.List;

public class ActionTracker {

    private boolean sprinting;
    private boolean sneaking;
    private boolean digging;
    private long lastDigTime;

    private boolean usingItem;
    private int lastUseItemTick;
    private boolean usingRiptide;
    private int pendingRiptideTicks;
    private int usingItemTicks;

    private boolean inventoryOpen;
    private int inventoryOpenTick;
    private long lastInventoryClose;
    private long lastWindowClick;
    private final List<Long> clickDelays = new ArrayList<>();
    private final List<Integer> clickedSlots = new ArrayList<>();

    private int currentSlot;
    private int lastSlot;

    // Vehicle steering (STEER_VEHICLE packet), consumed by the vehicle motion engine.
    private float vehicleForward;
    private float vehicleSideways;
    private int vehicleSteerTick = -100;

    // Known client input (1.21.2+ PLAYER_INPUT packet): the exact WASD/jump/
    // shift/sprint state the client used this tick. When fresh, the engine's
    // input brute-force collapses to a single exact candidate.
    private boolean inputForward, inputBackward, inputLeft, inputRight, inputJump, inputShift, inputSprint;
    private int lastInputPacketTick = -100;
    private int lastClientTickEndTick = -100;

    public void reset() {
        this.sprinting = false;
        this.sneaking = false;
        this.digging = false;
        this.lastDigTime = 0;
        this.usingItem = false;
        this.usingRiptide = false;
        this.pendingRiptideTicks = 0;
        this.usingItemTicks = 0;
        this.inventoryOpen = false;
        this.inventoryOpenTick = 0;
        this.lastInventoryClose = -1000;
        this.lastUseItemTick = -1000;
        this.vehicleSteerTick = -1000;
        this.lastInputPacketTick = -1000;
        this.lastClientTickEndTick = -1000;
        this.lastWindowClick = 0;
        this.clickDelays.clear();
        this.clickedSlots.clear();
    }

    public boolean isSprinting() { return sprinting; }
    public void setSprinting(boolean b) { this.sprinting = b; }

    public boolean isSneaking() { return sneaking; }
    public void setSneaking(boolean b) { this.sneaking = b; }

    public boolean isDigging() { return digging; }

    public void setDigging(boolean b) {
        this.digging = b;
        if (b) {
            this.lastDigTime = System.currentTimeMillis();
        }
    }

    public boolean isUsingItem() { return usingItem; }

    public void setUsingItem(boolean b, int ticksTracked) {
        this.usingItem = b;
        if (!b) this.lastUseItemTick = ticksTracked;
    }

    public int getLastUseItemTick() { return lastUseItemTick; }
    public boolean isUsingRiptide() { return usingRiptide; }

    public void setUsingRiptide(boolean b) {
        this.usingRiptide = b;
        if (b) this.pendingRiptideTicks = 0;
    }

    public int getPendingRiptideTicks() { return pendingRiptideTicks; }
    public void setPendingRiptideTicks(int t) { this.pendingRiptideTicks = t; }
    public void incrementPendingRiptideTicks() { this.pendingRiptideTicks++; }
    public void resetPendingRiptideTicks() { this.pendingRiptideTicks = 0; }
    public int getUsingItemTicks() { return usingItemTicks; }
    public void setUsingItemTicks(int t) { this.usingItemTicks = t; }
    public void incrementUsingItemTicks() { this.usingItemTicks++; }
    public boolean isInventoryOpen() { return inventoryOpen; }

    public void setInventoryOpen(boolean b, int ticksTracked) {
        this.inventoryOpen = b;
        if (b) {
            this.inventoryOpenTick = ticksTracked;
        } else {
            this.lastInventoryClose = System.currentTimeMillis();
        }
    }

    public int getInventoryOpenTick() { return inventoryOpenTick; }
    public long getLastInventoryClose() { return lastInventoryClose; }
    public long getLastWindowClick() { return lastWindowClick; }
    public void setLastWindowClick(long t) { this.lastWindowClick = t; }
    public List<Long> getClickDelays() { return clickDelays; }
    public List<Integer> getClickedSlots() { return clickedSlots; }
    public int getCurrentSlot() { return currentSlot; }
    public void setCurrentSlot(int s) { this.currentSlot = s; }
    public int getLastSlot() { return lastSlot; }
    public void setLastSlot(int s) { this.lastSlot = s; }

    public void setVehicleSteer(float forward, float sideways, int tick) {
        this.vehicleForward = forward;
        this.vehicleSideways = sideways;
        this.vehicleSteerTick = tick;
    }
    public float getVehicleForward() { return vehicleForward; }
    public float getVehicleSideways() { return vehicleSideways; }
    public int getVehicleSteerTick() { return vehicleSteerTick; }

    public void setClientInput(boolean forward, boolean backward, boolean left, boolean right,
                               boolean jump, boolean shift, boolean sprint, int tick) {
        this.inputForward = forward;
        this.inputBackward = backward;
        this.inputLeft = left;
        this.inputRight = right;
        this.inputJump = jump;
        this.inputShift = shift;
        this.inputSprint = sprint;
        this.lastInputPacketTick = tick;
    }

    public void markClientTickEnd(int tick) {
        this.lastClientTickEndTick = tick;
    }

    /** True when the stored input state is fresh enough to trust as exact. */
    public boolean isInputKnown(int currentTick) {
        // 1 tick, not 3: the jump bit lags, and trusting a 3-tick-old "no
        // jump" suppressed the jump candidate on the takeoff tick and sent
        // the carried velocity diverging.
        int diffInput = currentTick - lastInputPacketTick;
        int diffEnd = currentTick - lastClientTickEndTick;
        return (diffInput >= 0 && diffInput <= 1) || (diffEnd >= 0 && diffEnd <= 1);
    }
    public boolean isInputForward() { return inputForward; }
    public boolean isInputBackward() { return inputBackward; }
    public boolean isInputLeft() { return inputLeft; }
    public boolean isInputRight() { return inputRight; }
    public boolean isInputJump() { return inputJump; }
    public boolean isInputShift() { return inputShift; }
    public boolean isInputSprint() { return inputSprint; }

    public void handleTick() {
        if (this.usingItem) {
            this.usingItemTicks++;
            if (this.usingItemTicks > 35) {
                this.usingItem = false;
                this.usingItemTicks = 0;
            }
        } else {
            this.usingItemTicks = 0;
        }

        if (this.digging && System.currentTimeMillis() - this.lastDigTime > 1000) {
            this.digging = false;
        }
    }
}