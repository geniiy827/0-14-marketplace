package com.velocitypowered.api.plugin.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
public @interface DataDirectory {}
