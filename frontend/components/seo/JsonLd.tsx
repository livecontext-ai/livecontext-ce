// Renders a schema.org JSON-LD block. Server-safe (no client hooks).
// `<` is escaped so a literal "</script>" inside string values cannot break
// out of the script element (the data is ours, this is defense-in-depth).
export default function JsonLd({ data }: { data: object }) {
  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{ __html: JSON.stringify(data).replace(/</g, '\\u003c') }}
    />
  );
}
