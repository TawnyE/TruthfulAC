package ret.tawny.truthful.utils.hitbox;

import org.bukkit.Location;
import org.bukkit.util.Vector;

public class SimpleHitbox {
    public double minX, minY, minZ;
    public double maxX, maxY, maxZ;

    public SimpleHitbox(double x, double y, double z, double width, double height) {
        double r = width / 2.0;
        this.minX = x - r;
        this.minY = y;
        this.minZ = z - r;
        this.maxX = x + r;
        this.maxY = y + height;
        this.maxZ = z + r;
    }

    // Expand the hitbox (for leniency)
    /** Returns an expanded copy of this hitbox without mutating the original. */
    public SimpleHitbox cloneExpand(double expansion) {
        SimpleHitbox copy = new SimpleHitbox(0, 0, 0, 1, 1);
        copy.minX = this.minX - expansion;
        copy.minY = this.minY - expansion;
        copy.minZ = this.minZ - expansion;
        copy.maxX = this.maxX + expansion;
        copy.maxY = this.maxY + expansion;
        copy.maxZ = this.maxZ + expansion;
        return copy;
    }

    public void expand(double expansion) {
        this.minX -= expansion;
        this.minY -= expansion;
        this.minZ -= expansion;
        this.maxX += expansion;
        this.maxY += expansion;
        this.maxZ += expansion;
    }

    // Calculate closest distance from a point to this box
    public double distance(Vector point) {
        double dx = Math.max(Math.max(minX - point.getX(), 0), point.getX() - maxX);
        double dy = Math.max(Math.max(minY - point.getY(), 0), point.getY() - maxY);
        double dz = Math.max(Math.max(minZ - point.getZ(), 0), point.getZ() - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean isVecInside(Vector point) {
        return point.getX() >= minX && point.getX() <= maxX
                && point.getY() >= minY && point.getY() <= maxY
                && point.getZ() >= minZ && point.getZ() <= maxZ;
    }

    /**
     * Check if a Ray intersects this box using the "Slab method".
     *
     * FIXED: Division by zero when a direction component is exactly 0.0
     * (e.g., looking straight along an axis). The old code produced NaN/Infinity
     * which caused ALL comparisons to return false, silently breaking reach
     * and hitbox detection for axis-aligned aim.
     *
     * The fix uses the standard IEEE 754 approach: when direction is 0, we
     * compute tMin/tMax using the inverse (which becomes ±Infinity), and
     * Java's Math comparisons handle ±Infinity correctly — meaning the slab
     * test naturally works if the origin is between the slab planes.
     */
    public boolean intersectsRay(Vector origin, Vector direction, double maxDistance) {
        // Use inverse direction to avoid division by zero issues.
        // In IEEE 754, 1.0/0.0 = Infinity, 1.0/-0.0 = -Infinity which is handled correctly.
        double invDirX = 1.0 / direction.getX();
        double invDirY = 1.0 / direction.getY();
        double invDirZ = 1.0 / direction.getZ();

        double t1 = (minX - origin.getX()) * invDirX;
        double t2 = (maxX - origin.getX()) * invDirX;

        double tMin = Math.min(t1, t2);
        double tMax = Math.max(t1, t2);

        double ty1 = (minY - origin.getY()) * invDirY;
        double ty2 = (maxY - origin.getY()) * invDirY;

        double tyMin = Math.min(ty1, ty2);
        double tyMax = Math.max(ty1, ty2);

        if (tMin > tyMax || tyMin > tMax) return false;
        tMin = Math.max(tMin, tyMin);
        tMax = Math.min(tMax, tyMax);

        double tz1 = (minZ - origin.getZ()) * invDirZ;
        double tz2 = (maxZ - origin.getZ()) * invDirZ;

        double tzMin = Math.min(tz1, tz2);
        double tzMax = Math.max(tz1, tz2);

        if (tMin > tzMax || tzMin > tMax) return false;
        tMin = Math.max(tMin, tzMin);
        tMax = Math.min(tMax, tzMax);

        // Handle NaN from 0*Infinity (when origin is exactly on a slab boundary
        // and direction is 0 along that axis). In this case, treat as intersecting
        // since the origin is inside the slab.
        if (Double.isNaN(tMin)) tMin = Double.NEGATIVE_INFINITY;
        if (Double.isNaN(tMax)) tMax = Double.POSITIVE_INFINITY;

        // tMax < 0 means the box is behind the ray origin
        if (tMax < 0) return false;

        // tMin must be within [0, maxDistance] — if tMin < 0, origin is inside box (valid hit)
        return tMin < maxDistance && tMax >= 0;
    }
}