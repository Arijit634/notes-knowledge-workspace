package org.notesknowledge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Acquire transient dispatch serialization BEFORE the owning transaction interceptor. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CoordinatedMutation {
    int ownerArgument() default 0;
    int noteArgument() default 1;
}
