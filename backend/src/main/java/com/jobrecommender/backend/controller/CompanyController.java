package com.jobrecommender.backend.controller;

import com.jobrecommender.backend.entity.Company;
import com.jobrecommender.backend.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping
    public Page<Company> getAllCompanies(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return companyService.findAll(PageRequest.of(page, size));
    }

    @GetMapping("/search")
    public Page<Company> searchCompanies(
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return companyService.searchByName(name, PageRequest.of(page, size));
    }

    @PostMapping
    public Company createCompany(@RequestBody Company company) {
        return companyService.createOrMerge(company);
    }
}
