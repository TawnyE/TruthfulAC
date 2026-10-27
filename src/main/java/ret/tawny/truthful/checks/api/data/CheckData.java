package ret.tawny.truthful.checks.api.data;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention(RUNTIME)
@Target(TYPE)
public @interface CheckData {
    char order();

    CheckType type();


    String displayName() default "";

    /**
     * Marks the check as experimental. Experimental checks are still fully
     * functional, but their tag is shown in alerts / hover / GUI (placement is
     * configurable, see messages.check-tags) so staff know to trust them less.
     */
    boolean experimental() default false;

    /** Free-form extra tags, e.g. {"PHYSICS", "HEURISTIC"}. Shown like experimental. */
    String[] tags() default {};
}
