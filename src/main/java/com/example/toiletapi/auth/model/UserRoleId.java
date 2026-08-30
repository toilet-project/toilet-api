package com.example.toiletapi.auth.model;

import java.io.Serializable;

public record UserRoleId(Long userId, Role role) implements Serializable { }
