package com.changegraph.backend.github;

import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RepositoryCommitCursor {

    private final ProjectRepositoryLinkRepository links;

    public RepositoryCommitCursor(ProjectRepositoryLinkRepository links) {
        this.links = links;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void remember(UUID projectId, String sha) {
        if (projectId == null || sha == null || sha.isBlank()) {
            return;
        }
        links.findByProjectId(projectId).ifPresent(link -> {
            if (!sha.equals(link.getLastSeenCommitSha())) {
                link.setLastSeenCommitSha(sha);
            }
        });
    }
}
