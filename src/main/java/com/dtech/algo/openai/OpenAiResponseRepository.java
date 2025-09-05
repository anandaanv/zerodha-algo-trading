package com.dtech.algo.openai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OpenAiResponseRepository extends JpaRepository<OpenAiResponse, Long> {
}
