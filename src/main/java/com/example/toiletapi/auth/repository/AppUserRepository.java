package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> { }
