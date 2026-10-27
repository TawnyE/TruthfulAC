package ret.tawny.truthful.utils.world;

import ret.tawny.truthful.data.world.CompensatedWorld;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * Clip Y, then X, then Z; if a wall blocks, zero that axis. Step-up:
 * retry the move lifted by stepHeight, settle back down, keep whichever
 * travelled further. Envelopes are MAGNITUDES - the sweep extends both
 * directions on every axis or half the world is invisible (learned that
 * one the hard way). Boxes are gathered once per tick and shared across
 * every candidate, because gathering is the expensive part. Things that
 * colide get zeroed on the hit axis, same as vanilla.
 */
public final class Collider {

    private Collider() {}

    private static final double EPSILON = 1.0E-7;

    public static final class Result {
        public double x, y, z;
        public boolean collidedX, collidedY, collidedZ;
        public boolean collidedDown;
        public boolean stepped;

        public void set(double x, double y, double z,
                       boolean cx, boolean cy, boolean cz,
                       boolean down, boolean stepped) {
            this.x = x; this.y = y; this.z = z;
            this.collidedX = cx; this.collidedY = cy; this.collidedZ = cz;
            this.collidedDown = down; this.stepped = stepped;
        }
    }

    /**
     * A prepared collision world-slice. Boxes are world-coordinate
     * {minX, minY, minZ, maxX, maxY, maxZ} triples of doubles, flat-packed.
     */
    public static final class Session {
        private double[] boxes;
        private int count;

        Session(double[] boxes, int count) {
            this.boxes = boxes;
            this.count = count;
        }

        /** Grows (or seeds) the backing array to hold boxCount boxes; returns it. */
        double[] capacityFor(int boxCount) {
            int needed = boxCount * 6;
            if (boxes == null || boxes.length < needed) {
                double[] grown = new double[Math.max(needed, boxes == null ? 96 : boxes.length * 2)];
                if (boxes != null && count * 6 > 0) {
                    System.arraycopy(boxes, 0, grown, 0, count * 6);
                }
                boxes = grown;
            }
            return boxes;
        }

        void setCount(int count) { this.count = count; }

        /**
         * Resolves one candidate motion for a player box at (px, py, pz)
         * (feet position) with the given width/height.
         */
        public void resolve(double px, double py, double pz,
                            double width, double height,
                            double motX, double motY, double motZ,
                            boolean onGround, double stepHeight,
                            Result out) {
            double half = width / 2.0;
            double minX = px - half, maxX = px + half;
            double minZ = pz - half, maxZ = pz + half;
            double minY = py, maxY = py + height;

            double rx = motX, ry = motY, rz = motZ;
            boolean cX = false, cY = false, cZ = false;
            boolean down = false;

            if (count == 0) {
                out.set(rx, ry, rz, false, false, false, false, false);
                return;
            }

            // ---- Y axis first (vanilla order) ----
            if (motY != 0.0) {
                double clamped = clipY(minX, minZ, maxX, maxZ, motY, minY, maxY);
                if (clamped != motY) {
                    cY = true;
                    if (motY < 0.0 && clamped >= motY - EPSILON && clamped <= 0.0) down = true;
                }
                ry = clamped;
            }
            minY += ry;
            maxY += ry;

            // ---- X axis ----
            if (motX != 0.0) {
                double clamped = clipAxis(true, minY, minZ, maxY, maxZ, motX, minX, maxX);
                if (clamped != motX) cX = true;
                rx = clamped;
            }
            minX += rx;
            maxX += rx;

            // ---- Z axis ----
            if (motZ != 0.0) {
                double clamped = clipAxis(false, minY, minX, maxY, maxX, motZ, minZ, maxZ);
                if (clamped != motZ) cZ = true;
                rz = clamped;
            }

            // ---- Step-up: horizontal collision while on ground -> try lifted ----
            boolean stepped = false;
            if ((cX || cZ) && onGround && stepHeight > 0.0) {
                // 1. Lift box by full stepHeight (independent of motY so negative vertical motion like gravity doesn't truncate the lift below the step)
                double stepMotY = stepHeight;

                double syMin = py, syMax = py + height;
                double sMinX = px - half, sMaxX = px + half;
                double sMinZ = pz - half, sMaxZ = pz + half;

                double sRy = stepMotY != 0.0
                        ? clipY(sMinX, sMinZ, sMaxX, sMaxZ, stepMotY, syMin, syMax)
                        : 0.0;
                syMin += sRy; syMax += sRy;
                boolean syC = sRy != stepMotY;

                double sRx = motX != 0.0
                        ? clipAxis(true, syMin, sMinZ, syMax, sMaxZ, motX, sMinX, sMaxX)
                        : 0.0;
                sMinX += sRx; sMaxX += sRx;
                boolean sxC = sRx != motX;

                double sRz = motZ != 0.0
                        ? clipAxis(false, syMin, sMinX, syMax, sMaxX, motZ, sMinZ, sMaxZ)
                        : 0.0;
                boolean szC = sRz != motZ;

                // Settle back down by the FULL resolved up-motion: the
                // down-clip lands the candidate on the step top (vanilla).
                // 3. Settle back down by the lifted amount minus motY
                double settle = sRy - motY;
                double sDown = 0.0;
                boolean sDownC = false;
                if (settle > 0.0) {
                    double clampedDown = clipY(sMinX, sMinZ, sMaxX, sMaxZ, -settle, syMin, syMax);
                    sDown = clampedDown;
                    sDownC = sDown != -settle;
                    syMin += sDown; syMax += sDown;
                }

                double baseDist = rx * rx + rz * rz;
                double stepDist = sRx * sRx + sRz * sRz;
                if (stepDist > baseDist + EPSILON) {
                    rx = sRx; rz = sRz;
                    ry = sRy + sDown;
                    cX = sxC; cZ = szC;
                    cY = syC || sDownC;
                    down = down || sDownC;
                    stepped = true;
                }
            }

            out.set(rx, ry, rz, cX, cY, cZ, down, stepped);
        }

        /** Clips motion on the Y axis against all gathered boxes. */
        private double clipY(double minX, double minZ, double maxX, double maxZ,
                             double motY, double curMinY, double curMaxY) {
            double delta = motY;
            for (int i = 0; i < count; i++) {
                int o = i * 6;
                double bMinX = boxes[o], bMinY = boxes[o + 1], bMinZ = boxes[o + 2];
                double bMaxX = boxes[o + 3], bMaxY = boxes[o + 4], bMaxZ = boxes[o + 5];
                if (maxX <= bMinX + EPSILON || minX >= bMaxX - EPSILON) continue;
                if (maxZ <= bMinZ + EPSILON || minZ >= bMaxZ - EPSILON) continue;
                if (motY > 0.0 && curMaxY <= bMinY + EPSILON) {
                    double allowed = bMinY - curMaxY;
                    if (allowed < delta) delta = allowed;
                } else if (motY < 0.0 && curMinY >= bMaxY - EPSILON) {
                    double allowed = bMaxY - curMinY;
                    if (allowed > delta) delta = allowed;
                }
            }
            return delta;
        }

        /** Clips motion on the X axis (xAxis=true) or Z axis (xAxis=false). */
        private double clipAxis(boolean xAxis,
                                double minY, double minOther, double maxY, double maxOther,
                                double mot, double curMinA, double curMaxA) {
            double delta = mot;
            for (int i = 0; i < count; i++) {
                int o = i * 6;
                double bMinX = boxes[o], bMinY = boxes[o + 1], bMinZ = boxes[o + 2];
                double bMaxX = boxes[o + 3], bMaxY = boxes[o + 4], bMaxZ = boxes[o + 5];

                // Overlap on Y is required regardless of the moving axis.
                if (maxY <= bMinY + EPSILON || minY >= bMaxY - EPSILON) continue;
                // Overlap on the other horizontal axis.
                if (xAxis) {
                    if (maxOther <= bMinZ + EPSILON || minOther >= bMaxZ - EPSILON) continue;
                } else {
                    if (maxOther <= bMinX + EPSILON || minOther >= bMaxX - EPSILON) continue;
                }

                double bMinA = xAxis ? bMinX : bMinZ;
                double bMaxA = xAxis ? bMaxX : bMaxZ;
                if (mot > 0.0 && curMaxA <= bMinA + EPSILON) {
                    double allowed = bMinA - curMaxA;
                    if (allowed < delta) delta = allowed;
                } else if (mot < 0.0 && curMinA >= bMaxA - EPSILON) {
                    double allowed = bMaxA - curMinA;
                    if (allowed > delta) delta = allowed;
                }
            }
            return delta;
        }
    }

    /**
     * Scratch session per thread: prepare/resolve sequences never nest on a
     * single thread (movement tick and ground probe are strictly
     * sequential), so the gather buffer can be reused instead of allocating
     * two arrays per movement packet per player.
     */
    private static final ThreadLocal<Session> SCRATCH = ThreadLocal.withInitial(() -> new Session(new double[128], 0));

    /**
     * Gathers every collision box overlapping the swept envelope of a player
     * box at (px, py, pz) with a motion envelope (envX..Z) that must cover the
     * largest candidate motion (plus step height on Y). The returned session
     * is THREAD-SCRATCH: use it before calling prepare again on this thread.
     */
    public static Session prepare(CompensatedWorld world,
                                  double px, double py, double pz,
                                  double width, double height,
                                  double envX, double envY, double envZ) {
        double half = width / 2.0;

        // envX/envY/envZ are MAGNITUDES: the sweep extends symmetrically in
        // both directions on every axis. (A previous version extended only
        // one direction per axis, which silently excluded everything below
        // the feet - ground probes, floor truncation and negative-direction
        // wall clips never saw a single block.)
        double sweepMinX = px - half - Math.max(0.0, envX);
        double sweepMaxX = px + half + Math.max(0.0, envX);
        double sweepMinY = py - Math.max(0.0, envY);
        double sweepMaxY = py + height + Math.max(0.0, envY);
        double sweepMinZ = pz - half - Math.max(0.0, envZ);
        double sweepMaxZ = pz + half + Math.max(0.0, envZ);

        int x0 = floor(sweepMinX), x1 = floor(sweepMaxX);
        int y0 = floor(sweepMinY), y1 = floor(sweepMaxY);
        int z0 = floor(sweepMinZ), z1 = floor(sweepMaxZ);

        // Upper bound on boxes: cells scanned * (max shapes per block).
        int maxCells = (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        Session session = SCRATCH.get();
        double[] buffer = session.capacityFor(maxCells * 8);
        int count = 0;

        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    int id = world.getBlockId(bx, by, bz);
                    if (id == 0) continue;
                    List<CollisionShapes.Box> shapes = CollisionShapes.of(id);
                    if (shapes.isEmpty()) continue;
                    for (CollisionShapes.Box box : shapes) {
                        double wMinX = bx + box.minX(), wMaxX = bx + box.maxX();
                        double wMinY = by + box.minY(), wMaxY = by + box.maxY();
                        double wMinZ = bz + box.minZ(), wMaxZ = bz + box.maxZ();
                        // Only keep boxes actually reachable by the sweep.
                        if (wMaxX <= sweepMinX || wMinX >= sweepMaxX) continue;
                        if (wMaxY <= sweepMinY || wMinY >= sweepMaxY) continue;
                        if (wMaxZ <= sweepMinZ || wMinZ >= sweepMaxZ) continue;

                        if (count * 6 + 6 > buffer.length) {
                            buffer = session.capacityFor(count + 1024);
                        }
                        int o = count * 6;
                        buffer[o] = wMinX; buffer[o + 1] = wMinY; buffer[o + 2] = wMinZ;
                        buffer[o + 3] = wMaxX; buffer[o + 4] = wMaxY; buffer[o + 5] = wMaxZ;
                        count++;
                    }
                }
            }
        }

        session.setCount(count);
        return session;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
