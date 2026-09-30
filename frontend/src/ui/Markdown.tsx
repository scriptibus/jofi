// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import ReactMarkdown, { type Components } from "react-markdown";
import rehypeSanitize, { type Options as SanitizeSchema } from "rehype-sanitize";
import { ExternalLink } from "./Link";

const LINK_PROTOCOLS = new Set(["http:", "https:", "mailto:"]);

/**
 * The URL if it is absolute and uses an allowed scheme (http, https, mailto), else "" (no link).
 * Relative URLs are refused too: in untrusted text they would point into Jofi itself.
 */
export function safeHref(url: string): string {
  if (!URL.canParse(url)) return "";
  return LINK_PROTOCOLS.has(new URL(url).protocol) ? url : "";
}

/**
 * What survives sanitising: text structure and links, nothing else. No raw HTML, no images (a remote
 * image leaks the user's IP address and works as a tracking pixel), no attributes but `href` and `start`.
 */
export const MARKDOWN_SCHEMA: SanitizeSchema = {
  tagNames: [
    "p",
    "br",
    "hr",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "strong",
    "em",
    "del",
    "code",
    "pre",
    "blockquote",
    "ul",
    "ol",
    "li",
    "a",
  ],
  attributes: { a: ["href"], ol: ["start"] },
  protocols: { href: ["http", "https", "mailto"] },
  strip: ["script", "style"],
  allowComments: false,
  allowDoctypes: false,
};

// Headings inside Markdown sit below the section heading (h2) of the page that shows them.
const components: Components = {
  h1: ({ node: _node, ...props }) => <h3 className="text-h3" {...props} />,
  h2: ({ node: _node, ...props }) => <h4 className="font-semibold" {...props} />,
  h3: ({ node: _node, ...props }) => <h5 className="font-semibold" {...props} />,
  h4: ({ node: _node, ...props }) => <h6 className="font-semibold" {...props} />,
  h5: ({ node: _node, ...props }) => <h6 className="font-semibold" {...props} />,
  h6: ({ node: _node, ...props }) => <h6 className="font-semibold" {...props} />,
  ul: ({ node: _node, ...props }) => <ul className="list-disc pl-6" {...props} />,
  ol: ({ node: _node, ...props }) => <ol className="list-decimal pl-6" {...props} />,
  blockquote: ({ node: _node, ...props }) => (
    <blockquote className="border-line border-l-4 pl-4 text-muted" {...props} />
  ),
  pre: ({ node: _node, ...props }) => (
    <pre className="overflow-x-auto rounded bg-sunken p-3 font-data" {...props} />
  ),
  code: ({ node: _node, ...props }) => <code className="font-data" {...props} />,
  a: ({ href, children }) => {
    const safe = href === undefined ? "" : safeHref(href);
    return safe === "" ? <span>{children}</span> : <ExternalLink href={safe}>{children}</ExternalLink>;
  },
};

export interface MarkdownProps {
  /** Markdown from the user or, untrusted, from an AI or the web. */
  children: string;
  className?: string;
}

/**
 * Renders Markdown safely, also when it is untrusted (an AI-generated profile may carry text from web
 * pages): raw HTML is dropped, the result is sanitised against `MARKDOWN_SCHEMA`, and links open as
 * `ExternalLink` (`noopener noreferrer nofollow`) only with an http, https or mailto URL.
 */
export function Markdown({ children, className }: MarkdownProps) {
  return (
    <div className={["flex flex-col gap-3 break-words", className].filter(Boolean).join(" ")}>
      <ReactMarkdown
        skipHtml
        rehypePlugins={[[rehypeSanitize, MARKDOWN_SCHEMA]]}
        urlTransform={safeHref}
        components={components}
      >
        {children}
      </ReactMarkdown>
    </div>
  );
}
