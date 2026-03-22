package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.entity.Location;
import com.jobrecommender.backend.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    public Page<Location> getAllLocations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return locationService.findAll(PageRequest.of(page, size));
    }

    @GetMapping("/search")
    public Page<Location> searchLocations(
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return locationService.searchByName(name, PageRequest.of(page, size));
    }

    @PostMapping
    public Location createLocation(@RequestBody Location location) {
        return locationService.createOrMerge(location);
    }
}
