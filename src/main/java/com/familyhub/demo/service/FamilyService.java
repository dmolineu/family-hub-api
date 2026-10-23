package com.familyhub.demo.service;

import com.familyhub.demo.dto.FamilyRequest;
import com.familyhub.demo.dto.FamilyResponse;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.mapper.FamilyMapper;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ServiceUnavailableException;
import com.familyhub.demo.weather.GeoLocation;
import com.familyhub.demo.weather.WeatherProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FamilyService {
    private final FamilyRepository familyRepository;
    private final WeatherProvider weatherProvider;

    @Transactional
    public FamilyResponse findFamilyResponse(UUID familyId) {
        Family family = familyRepository.findById(familyId)
                .orElseThrow(() -> new ResourceNotFoundException("Family", familyId));
        return FamilyMapper.toDto(family);
    }

    public Family findFamilyById(UUID familyId) {
        return familyRepository.findById(familyId)
                .orElseThrow(() -> new ResourceNotFoundException("Family", familyId));
    }

    @Transactional
    public FamilyResponse updateFamily(UUID id, FamilyRequest family) {
        Family toBeUpdated = findFamilyById(id);

        if (family.name() != null) {
            toBeUpdated.setName(family.name());
        }

        if (family.username() != null) {
            toBeUpdated.setUsername(family.username());
        }

        if (family.timezone() != null) {
            toBeUpdated.setTimezone(FamilyTimezoneResolver.normalizeRequestedTimezone(family.timezone()));
        }

        if (family.weatherLocation() != null) {
            applyWeatherLocation(toBeUpdated, family.weatherLocation());
        }

        return FamilyMapper.toDto(familyRepository.save(toBeUpdated));
    }

    /** Blank clears the location; anything else must geocode to a real place. */
    private void applyWeatherLocation(Family family, String requested) {
        String query = requested.trim();
        if (query.isEmpty()) {
            family.setWeatherLocation(null);
            family.setLatitude(null);
            family.setLongitude(null);
            return;
        }
        Optional<GeoLocation> place;
        try {
            place = weatherProvider.geocode(query);
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Could not look up that location right now; try again later.", ex);
        }
        GeoLocation location = place.orElseThrow(() -> new BadRequestException(
                "Could not find that location. Try a city and state, or a postal code."));
        family.setWeatherLocation(location.displayName());
        family.setLatitude(location.latitude());
        family.setLongitude(location.longitude());
    }

    @Transactional
    public void deleteFamily(UUID id) {
        Family toBeDeleted = findFamilyById(id);
        familyRepository.delete(toBeDeleted);
    }
}
