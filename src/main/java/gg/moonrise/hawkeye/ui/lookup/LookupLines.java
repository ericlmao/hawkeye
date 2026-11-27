package gg.moonrise.hawkeye.ui.lookup;

import gg.moonrise.engine.message.Message;
import gg.moonrise.hawkeye.config.message.GeneralMessages;
import gg.moonrise.hawkeye.config.message.InspectMessages;
import gg.moonrise.hawkeye.config.message.LookupMessages;
import gg.moonrise.hawkeye.config.message.MessagesConfig;
import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.core.service.lookup.LookupWarning;
import gg.moonrise.hawkeye.core.service.lookup.page.PageResult;
import gg.moonrise.hawkeye.core.service.lookup.page.PageView;
import gg.moonrise.hawkeye.entity.query.Area;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;

/**
 * Renders every lookup reply from {@code messages.yml}: result pages (with the inspector's header for
 * a page about one block), counts, and why a lookup has no result. A pure function of the messages and
 * the {@link EventLines} it renders events with.
 *
 * @param messages the messages
 * @param events   the event line renderer
 */
public record LookupLines(@NotNull MessagesConfig messages, @NotNull EventLines events) {

    /**
     * The reply to opening or turning a page.
     *
     * @param result the page or why there is none
     * @return the lines to send
     */
    public @NotNull List<Component> page(@NotNull PageResult result) {
        LookupMessages lookup = messages.getLookup();
        return switch (result) {
            case PageResult.Shown shown -> page(shown.view());
            case PageResult.NoSession noSession -> List.of(Message.of(lookup.getNoSession()).asComponent());
            case PageResult.OutOfRange outOfRange -> List.of(Message.of(lookup.getPageOutOfRange()).asComponent(
                    Placeholder.unparsed("pages", Integer.toString(outOfRange.pages()))));
            case PageResult.Unavailable unavailable -> List.of(problem(unavailable.problem()));
        };
    }

    /**
     * A result page: header, one line per event, the footer when there is more than one page, then the
     * shards that could not be read.
     *
     * @param view the page
     * @return the lines to send
     */
    public @NotNull List<Component> page(@NotNull PageView view) {
        List<Component> lines = new ArrayList<>();
        if (view.events().isEmpty()) {
            String empty = view.query().area() instanceof Area.Block ? messages.getInspect().getNoHistory()
                    : messages.getLookup().getNoResults();
            lines.add(Message.of(empty).asComponent());
        } else {
            lines.add(header(view));
            view.events().forEach(event -> lines.add(events.line(event)));
            if (view.page() > 1 || view.hasMore()) {
                lines.add(footer(view));
            }
        }
        lines.addAll(warnings(view.warnings()));
        return lines;
    }

    /**
     * The reply to a {@code #count} lookup.
     *
     * @param outcome the count or why there is none
     * @return the lines to send
     */
    public @NotNull List<Component> count(@NotNull LookupOutcome outcome) {
        return switch (outcome) {
            case LookupOutcome.Count count -> {
                List<Component> lines = new ArrayList<>();
                lines.add(Message.of(messages.getLookup().getCount()).asComponent(
                        Placeholder.parsed("total", total(LookupTotal.of(count)))));
                lines.addAll(warnings(count.warnings()));
                yield lines;
            }
            case LookupOutcome.Page page -> {
                List<Component> lines = new ArrayList<>();
                lines.add(Message.of(messages.getLookup().getCount()).asComponent(
                        Placeholder.unparsed("total", Integer.toString(page.events().size()))));
                lines.addAll(warnings(page.warnings()));
                yield lines;
            }
            case LookupOutcome.Problem problem -> List.of(problem(problem));
        };
    }

    /**
     * Why a lookup has no result.
     *
     * @param problem the problem
     * @return the reply
     */
    public @NotNull Component problem(@NotNull LookupOutcome.Problem problem) {
        GeneralMessages general = messages.getGeneral();
        return switch (problem) {
            case LookupOutcome.NotReady notReady -> Message.of(general.getStorageNotReady()).asComponent();
            case LookupOutcome.Failed failed -> Message.of(general.getOperationFailed()).asComponent(
                    Placeholder.unparsed("reason", failed.reason()));
            case LookupOutcome.TimedOut timedOut -> Message.of(messages.getLookup().getTimedOut()).asComponent();
        };
    }

    private @NotNull Component header(@NotNull PageView view) {
        TagResolver.Single[] paging = paging(view);
        if (view.query().area() instanceof Area.Block block) {
            InspectMessages inspect = messages.getInspect();
            TagResolver.Single[] resolvers = new TagResolver.Single[paging.length + 4];
            resolvers[0] = Placeholder.unparsed("x", Integer.toString(block.x()));
            resolvers[1] = Placeholder.unparsed("y", Integer.toString(block.y()));
            resolvers[2] = Placeholder.unparsed("z", Integer.toString(block.z()));
            resolvers[3] = Placeholder.unparsed("world", block.world());
            System.arraycopy(paging, 0, resolvers, 4, paging.length);
            return Message.of(inspect.getHeader()).asComponent(resolvers);
        }
        return Message.of(messages.getLookup().getHeader()).asComponent(paging);
    }

    private @NotNull Component footer(@NotNull PageView view) {
        LookupMessages lookup = messages.getLookup();
        Component previous = view.page() > 1 ? Message.of(lookup.getPrevious()).asComponent(
                Placeholder.parsed("page", Integer.toString(view.page() - 1))) : Component.empty();
        Component next = view.hasMore() ? Message.of(lookup.getNext()).asComponent(
                Placeholder.parsed("page", Integer.toString(view.page() + 1))) : Component.empty();
        TagResolver.Single[] paging = paging(view);
        return Message.of(lookup.getFooter()).asComponent(paging[0], paging[1], paging[2],
                Placeholder.component("previous", previous), Placeholder.component("next", next));
    }

    /**
     * The paging placeholders of the header and footer templates.
     *
     * @param view the page
     * @return {@code <page>}, {@code <pages>} and {@code <total>}, in that order, all parsed
     */
    public @NotNull TagResolver.Single @NotNull [] paging(@NotNull PageView view) {
        LookupMessages lookup = messages.getLookup();
        String pages = switch (view.total()) {
            case LookupTotal.Exact exact -> Integer.toString(view.pagesFor(exact.events()));
            case LookupTotal.AtLeast atLeast -> atLeast(Integer.toString(view.pagesFor(atLeast.events())));
            case LookupTotal.Unknown unknown -> lookup.getUnknownTotal();
        };
        return new TagResolver.Single[] {
                Placeholder.parsed("page", Integer.toString(view.page())),
                Placeholder.parsed("pages", pages),
                Placeholder.parsed("total", total(view.total()))};
    }

    /** {@code <total>}: the number, a lower bound through {@code lookup.at-least}, or {@code unknown-total}. */
    private @NotNull String total(@NotNull LookupTotal total) {
        return switch (total) {
            case LookupTotal.Exact exact -> Long.toString(exact.events());
            case LookupTotal.AtLeast atLeast -> atLeast(Long.toString(atLeast.events()));
            case LookupTotal.Unknown unknown -> messages.getLookup().getUnknownTotal();
        };
    }

    /** A lower bound through {@code lookup.at-least}; the result is MiniMessage for a parsed placeholder. */
    private @NotNull String atLeast(@NotNull String value) {
        return messages.getLookup().getAtLeast().replace("<value>", value);
    }

    /**
     * One {@code lookup.shard-unavailable} line per shard that could not be read.
     *
     * @param warnings the shards left out of a result
     * @return the lines, empty when nothing was left out
     */
    public @NotNull List<Component> warnings(@NotNull List<LookupWarning> warnings) {
        String template = messages.getLookup().getShardUnavailable();
        return warnings.stream()
                .map(warning -> Message.of(template).asComponent(Placeholder.unparsed("shard", warning.shard()),
                        Placeholder.unparsed("reason", warning.reason())))
                .toList();
    }
}
