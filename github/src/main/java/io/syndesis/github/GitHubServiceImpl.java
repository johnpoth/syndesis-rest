/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.github;

import io.syndesis.core.SyndesisServerException;
import io.syndesis.core.Tokens;
import io.syndesis.git.GitWorkflow;
import org.eclipse.egit.github.core.Repository;
import org.eclipse.egit.github.core.RepositoryHook;
import org.eclipse.egit.github.core.User;
import org.eclipse.egit.github.core.client.RequestException;
import org.eclipse.egit.github.core.service.RepositoryService;
import org.eclipse.egit.github.core.service.UserService;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * @author roland
 * @since 08/03/2017
 */
@Service
@ConditionalOnProperty(value = "github.enabled", matchIfMissing = true, havingValue = "true")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class GitHubServiceImpl implements GitHubService {

    private static final LocalDate GITHUB_NOREPLY_EMAIL_CUTOFF = LocalDate.of(2017, 7, 18);

    private final RepositoryService repositoryService;
    private final UserService userService;
    private final GitWorkflow gitWorkflow;

    public GitHubServiceImpl(RepositoryService repositoryService, UserService userService, GitWorkflow gitWorkflow) {
        this.repositoryService = repositoryService;
        this.userService = userService;
        this.gitWorkflow = gitWorkflow;
    }

    @Override
    public String createOrUpdateProjectFiles(GithubRequest request) {
        try {
            Repository repository = getRepository(request.getRepoName());
            if (repository == null) {
                // New Repo
                repository = createRepository(request.getRepoName());
                // Add files
                doCreateOrUpdateFiles(request);
                // Set WebHook
                createWebHookAsBuildTrigger(repository, request.getWebHookUrl().orElseThrow(() -> new IllegalStateException("WebHook Url is required for setting up a new repository!")));
            } else {
                // Only create or update files
                doCreateOrUpdateFiles(request);
            }
            return repository.getCloneUrl();
        } catch (Exception e) {
            throw SyndesisServerException.launderThrowable(e);
        }
    }

    @Override
    public String getCloneURL(String repoName) throws IOException {
        Repository repository = getRepository(repoName);
        if( repository == null ) {
            return null;
        }
        return repository.getCloneUrl();
    }

    @Override
    public User getApiUser() throws IOException {
        final User user = userService.getUser();
        // if the user did not elect to publicly display his e-mail address, e-mail will be null
        // https://developer.github.com/v3/users/#get-a-single-user
        // let's put a dummy e-mail address then, as it is needed for the commit
        if (user.getEmail() == null) {
            // users before 2017-07-18 have their no-reply e-mail addresses in the form
            // username@users.noreply.github.com, and after that date
            // id+username@users.noreply.github.com
            // https://help.github.com/articles/about-commit-email-addresses/
            final LocalDate createdAt = user.getCreatedAt().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (createdAt.isAfter(GITHUB_NOREPLY_EMAIL_CUTOFF)) {
                user.setEmail(user.getId() + "+" + user.getLogin() + "@users.noreply.github.com");
            } else {
                user.setEmail(user.getLogin() + "@users.noreply.github.com");
            }
        }

        return user;
    }

    // =====================================================================================

    protected Repository getRepository(String name) throws IOException {
        User user = userService.getUser();
        try {
            return repositoryService.getRepository(user.getLogin(), name);
        } catch (RequestException e) {
            if (e.getStatus() != HttpStatus.NOT_FOUND.value()) {
                throw e;
            }
            return null;
        }
    }

    protected Repository createRepository(String name) throws IOException {
        Repository repo = new Repository();
        repo.setName(name);
        return repositoryService.createRepository(repo);
    }

    protected Repository getOrCreateRepository(String name) throws IOException {
        Repository repository = getRepository(name);
        if (repository != null) {
            return repository;
        }
        return createRepository(name);
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod") // PMD false positive
    private void doCreateOrUpdateFiles(GithubRequest request) throws IOException {
        Repository repo = getOrCreateRepository(request.getRepoName());
        gitWorkflow.createFiles(repo.getHtmlUrl(), repo.getName(),
            request.getAuthor(),
            request.getCommitMessage(),
            request.getFileContents(),
            new UsernamePasswordCredentialsProvider(Tokens.fetchProviderTokenFromKeycloak(Tokens.TokenProvider.GITHUB), "") );
    }

    private void createWebHookAsBuildTrigger(Repository repository, String url) throws IOException {
        if (url != null && url.length() > 0) {
            RepositoryHook hook = prepareRepositoryHookRequest(url);
            repositoryService.createHook(repository, hook);
        }
    }

    private RepositoryHook prepareRepositoryHookRequest(String url) {
        RepositoryHook hook = new RepositoryHook();
        Map<String, String> config = new HashMap<>();
        config.put("url", url);
        config.put("content_type", "json");
        hook.setConfig(config);
        hook.setName("web");
        hook.setActive(true);
        return hook;
    }
}
