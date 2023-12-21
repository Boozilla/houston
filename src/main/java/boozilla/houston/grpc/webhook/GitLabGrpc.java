package boozilla.houston.grpc.webhook;

import boozilla.houston.annotation.SecuredService;
import boozilla.houston.context.GitLabContext;
import boozilla.houston.grpc.webhook.command.Commands;
import com.google.protobuf.Empty;
import houston.grpc.webhook.ReactorGitLabGrpc;
import houston.vo.webhook.gitlab.NoteEvent;
import houston.vo.webhook.gitlab.PushEvent;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@SecuredService
public class GitLabGrpc extends ReactorGitLabGrpc.GitLabImplBase {
    private final Commands commands;
    private final String gitUrl;
    private final String targetBranch;
    private final String packageName;

    public GitLabGrpc(final Commands commands,
                      @Value("${git-url}") final String gitUrl,
                      @Value("${branch}") final String targetBranch,
                      @Value("${package-name}") final String packageName)
    {
        this.commands = commands;
        this.gitUrl = gitUrl;
        this.targetBranch = targetBranch;
        this.packageName = packageName;
    }

    @Override
    public Mono<Empty> push(final PushEvent request)
    {
        final var behavior = new GitLabBehavior(GitLabContext.current(gitUrl));

        behavior.uploadPayload(request.getProjectId(), request.getUserId(),
                        request.getRef(), request.getBefore(), request.getAfter())
                .flatMap(uploadPayload -> behavior.createIssue(uploadPayload)
                        .flatMap(issue -> behavior.linkIssues(issue.getIid(), uploadPayload)
                                .and(behavior.commentUploadPayload(issue.getIid(), uploadPayload))))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return Mono.just(Empty.getDefaultInstance());
    }

    @Override
    public Mono<Empty> note(final Mono<NoteEvent> request)
    {
        final var behavior = new GitLabBehavior(GitLabContext.current(gitUrl));

        request.filter(req -> req.getIssue().getLabelsList()
                        .stream()
                        .anyMatch(label -> label.getTitle().equalsIgnoreCase(this.targetBranch)))
                .flatMap(req -> {
                    final var projectId = req.getProject().getId();
                    final var issueIid = req.getIssue().getIid();
                    final var note = req.getObjectAttributes().getNote();
                    final var command = commands.find(note);

                    if(command.isPresent())
                    {
                        return command.get().run(this.packageName, projectId, issueIid, this.targetBranch, note, behavior);
                    }

                    return Mono.empty();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return Mono.just(Empty.getDefaultInstance());
    }
}
