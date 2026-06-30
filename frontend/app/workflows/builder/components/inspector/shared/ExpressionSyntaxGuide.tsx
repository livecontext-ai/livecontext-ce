/**
 * Expression Syntax Guide - Popover content showing available expression syntax
 * Extracted from ParameterColumn for better code organization (DRY principle)
 */

export function ExpressionSyntaxGuide() {
  return (
    <div className="space-y-3">
      <h4 className="font-semibold text-sm">Expression Syntax Guide</h4>
      <div className="space-y-3 text-xs text-slate-600 dark:text-slate-300">
        {/* Variables */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-cyan-500"></span>
            Variables
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li>Use <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{type:label.output.field}}`}</code> pattern</li>
            <li>MCP/Tool outputs: <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{mcp:label.output.field}}`}</code></li>
            <li>Trigger outputs: <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{trigger:label.output.field}}`}</code></li>
            <li>Split current item: <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{core:split.output.current_item.field}}`}</code> (in body nodes)</li>
            <li>Split full list: <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{core:split.output.items}}`}</code></li>
            <li>Find current row: <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{table:find.output.current_item.field}}`}</code> (in body nodes)</li>
            <li>Find row index: <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{table:find.output.current_index}}`}</code></li>
            <li>Loop iteration: <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{core:loop.output.iteration}}`}</code> (1-based)</li>
          </ul>
        </div>

        {/* Type Casting */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-indigo-500"></span>
            Type Casting
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">int()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">double()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">string()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">bool()</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{int(user_id) % 2 == 0}}`}</code></li>
          </ul>
        </div>

        {/* Arithmetic */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-blue-500"></span>
            Arithmetic
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">+</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">-</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">*</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">/</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">%</code> (modulo)</li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{quantity * price}}`}</code></li>
          </ul>
        </div>

        {/* Comparison */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-amber-500"></span>
            Comparison
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">==</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">!=</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">&lt;</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">&gt;</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">&lt;=</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">&gt;=</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{score > 90}}`}</code> | <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{status == 'active'}}`}</code></li>
          </ul>
        </div>

        {/* Logical */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-rose-500"></span>
            Logical
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">&amp;&amp;</code> (and) <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">||</code> (or) <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">!</code> (not)</li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{x > 0 && y < 100}}`}</code></li>
          </ul>
        </div>

        {/* Math Functions */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-lime-500"></span>
            Math Functions
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">abs()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">round(val, decimals)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">floor()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">ceil()</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">min(a, b)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">max(a, b)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">pow()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">sqrt()</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{round(price, 2)}}`}</code></li>
          </ul>
        </div>

        {/* String Functions */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
            String Functions
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">uppercase()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">lowercase()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">trim()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">length()</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">contains(str, search)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">startswith()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">endswith()</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">replace(str, old, new)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">substring(str, start, end)</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">split(str, delim)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">join(arr, delim)</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{contains(email, '@company.com')}}`}</code></li>
          </ul>
        </div>

        {/* Utility Functions */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-fuchsia-500"></span>
            Utility Functions
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">size()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">default(val, fallback)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">coalesce(a, b, c)</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">isempty()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">isnull()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">typeof()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">len()</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">json(val)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">fromjson(val)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">tojson(val)</code> - JSON parse/serialize (idempotent)</li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{size(items) > 0}}`}</code> | <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{default(name, 'Unknown')}}`}</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{json(mcp:fetch.output.body)}}`}</code> - typed Map/List from a JSON string</li>
          </ul>
        </div>

        {/* Date/Number Formatting */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-sky-500"></span>
            Date/Number Formatting
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`formatdate(value, 'yyyy-MM-dd')`}</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">formatnumber(val, decimals)</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`formatcurrency(val, 'EUR')`}</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">now()</code> <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">today()</code></li>
          </ul>
        </div>

        {/* Collection Access */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-purple-500"></span>
            Collection Access
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">[0]</code> - Array index | <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`['key']`}</code> - Map key</li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">.?[condition]</code> - Filter | <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">.![field]</code> - Projection</li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{items[0].name}}`}</code> | <code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{users.?[age > 18]}}`}</code></li>
          </ul>
        </div>

        {/* Ternary */}
        <div>
          <p className="font-semibold mb-1 text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            <span className="w-1.5 h-1.5 rounded-full bg-orange-500"></span>
            Ternary Operator
          </p>
          <ul className="list-disc list-inside space-y-0.5 ml-2">
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">condition ? valueIfTrue : valueIfFalse</code></li>
            <li><code className="bg-slate-100 dark:bg-slate-800 px-1 rounded">{`{{score > 50 ? 'pass' : 'fail'}}`}</code></li>
          </ul>
        </div>
      </div>
    </div>
  );
}
