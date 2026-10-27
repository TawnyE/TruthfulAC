package ret.tawny.truthful.utils.world;


public final class ChunkSnapshot {

    private static final int SECTION_BITS = 4;
    private static final int SECTION_MASK = 15;
    private static final int SECTION_SIZE = 1 << 12; // 4096

    // 64 sections cover -512 to +511, which is more than enough for modern MC (-64 to 320)
    private final Section[] sections = new Section[64];

    public void setBlock(int x, int y, int z, int globalId) {
        int sectionIndex = (y >> 4) + 32; // Offset by 32 to handle negative Y
        if (sectionIndex < 0 || sectionIndex >= 64) return;

        Section section = sections[sectionIndex];
        if (section == null) {
            synchronized (this) {
                section = sections[sectionIndex];
                if (section == null) {
                    section = sections[sectionIndex] = new Section();
                }
            }
        }

        // Flat index: (x * 256) + (z * 16) + y
        section.set(((x & SECTION_MASK) << 8) | ((z & SECTION_MASK) << 4) | (y & SECTION_MASK), globalId);
    }

    public int getBlockId(int x, int y, int z) {
        int sectionIndex = (y >> 4) + 32;
        if (sectionIndex < 0 || sectionIndex >= 64) return 0;

        Section section = sections[sectionIndex];
        if (section == null) return 0; // 0 is always Air

        return section.get(((x & SECTION_MASK) << 8) | ((z & SECTION_MASK) << 4) | (y & SECTION_MASK));
    }

    /**
     * A single 16x16x16 section. Cooperates by storing the section as a whole,
     * degrading to a dense int[4096] only when it actually becomes heterogeneous.
     * A uniformly-filled section costs a single int instead of a 16KB array.
     *
     * The uniform->dense transition is atomic: concurrent writers (several players'
     * packet threads applying the same edit to the shared chunk) cannot both observe
     * a null dense array and install their own, silently dropping one write. The
     * dense array is published once via CAS; losers of the race write on top of the
     * installed array instead of their discarded copy.
     */
    private static final class Section {
        private volatile int uniform;   // block ID when every entry in the section is equal (includes 0/Air)
        private final java.util.concurrent.atomic.AtomicReference<int[]> data =
                new java.util.concurrent.atomic.AtomicReference<>(); // dense storage once heterogeneous

        Section() {
            this.uniform = 0; // Air
        }

        int get(int index) {
            int[] d = this.data.get();
            if (d != null) return d[index];
            return uniform;
        }

        void set(int index, int globalId) {
            int[] d = this.data.get();
            if (d != null) {
                d[index] = globalId;
                return;
            }
            if (uniform == globalId) return; // no change

            // Expand uniform section into a dense array.
            int[] created = new int[SECTION_SIZE];
            if (uniform != 0) {
                java.util.Arrays.fill(created, uniform);
            }
            created[index] = globalId;
            if (this.data.compareAndSet(null, created)) return;
            // Lost the publish race; apply the write to the array another thread installed.
            this.data.get()[index] = globalId;
        }
    }
}