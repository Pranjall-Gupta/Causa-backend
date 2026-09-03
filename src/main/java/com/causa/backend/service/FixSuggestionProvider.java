package com.causa.backend.service;

import com.causa.backend.model.FixSuggestionRequest;
import com.causa.backend.model.FixSuggestionResponse;

public interface FixSuggestionProvider {
    FixSuggestionResponse generateFix(FixSuggestionRequest request);
}
