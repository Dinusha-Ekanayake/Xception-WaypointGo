import type { ReactNode } from "react";

// A short text with **bold**, *italic* and "- " bullets, drawn as elements.
// Nothing in it is treated as HTML, so text from outside the app is safe here.

function inline(text: string, keyBase: string): ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*|\*[^*]+\*)/g).map((part, i) => {
    if (part.startsWith("**") && part.endsWith("**") && part.length > 4) {
      return (
        <strong key={`${keyBase}-${i}`} className="font-semibold">
          {part.slice(2, -2)}
        </strong>
      );
    }
    if (part.startsWith("*") && part.endsWith("*") && part.length > 2) return <em key={`${keyBase}-${i}`}>{part.slice(1, -1)}</em>;
    return part;
  });
}

export function RichText({ text, className }: { text: string; className?: string }): React.JSX.Element {
  const blocks: ReactNode[] = [];
  let bullets: string[] = [];
  const flush = () => {
    if (bullets.length === 0) return;
    const items = bullets;
    const at = blocks.length;
    bullets = [];
    blocks.push(
      <ul key={`ul-${at}`} className="flex list-disc flex-col gap-1 pl-5">
        {items.map((item, i) => (
          <li key={i}>{inline(item, `li-${at}-${i}`)}</li>
        ))}
      </ul>,
    );
  };
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    const bullet = /^[-*•]\s+(.*)$/.exec(line);
    if (bullet) {
      bullets.push(bullet[1]!);
      continue;
    }
    flush();
    blocks.push(<p key={`p-${blocks.length}`}>{inline(line.replace(/^#+\s*/, ""), `p-${blocks.length}`)}</p>);
  }
  flush();
  return <div className={`flex flex-col gap-2 ${className ?? ""}`}>{blocks}</div>;
}
