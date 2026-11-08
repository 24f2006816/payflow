package com.payflow.common;

/** 404. Also used for resources that exist but belong to another tenant (prevents id enumeration). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " " + id + " not found");
    }
}
