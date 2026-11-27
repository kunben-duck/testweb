// node docs/openapi/export-workbench.mjs [--check]
// The combined contract is authoritative; each exported document uses only local refs.
import { readFileSync, writeFileSync } from 'node:fs';

const file = name => new URL(name, import.meta.url);
const source = JSON.parse(readFileSync(file('workbench-tasks-v1.json'), 'utf8'));

function project(isAgent) {
  const paths = Object.fromEntries(Object.entries(source.paths)
    .filter(([path]) => path.startsWith('/v1/chat/') !== isAgent));
  const components = { schemas: {} };
  const keep = (group, name) => {
    const entries = components[group] ??= {};
    if (Object.hasOwn(entries, name)) return;
    const definition = source.components[group]?.[name];
    if (!definition) throw new Error('Missing ' + group + '/' + name);
    entries[name] = definition;
    visit(definition);
  };
  function visit(value) {
    if (!value || typeof value !== 'object') return;
    if (value.$ref) {
      const [, root, group, name] = value.$ref.split('/');
      if (root !== 'components' || !value.$ref.startsWith('#/')) throw new Error(value.$ref);
      keep(group, name);
    }
    for (const rule of value.security ?? []) {
      for (const name of Object.keys(rule)) keep('securitySchemes', name);
    }
    Object.values(value).forEach(visit);
  }
  visit(paths);
  const usedTags = new Set(Object.values(paths).flatMap(p => Object.values(p).flatMap(op => op.tags)));
  return { ...source, info: { ...source.info, title: source.info.title + (isAgent ? '：产品与 Agent' : '：前端') },
    tags: source.tags.filter(tag => usedTags.has(tag.name)), paths, components };
}

for (const audience of ['agent', 'frontend']) {
  const target = file('workbench-' + audience + '-v1.json');
  const json = JSON.stringify(project(audience === 'agent'), null, 2) + '\n';
  if (!process.argv.includes('--check')) writeFileSync(target, json);
  else if (readFileSync(target, 'utf8') !== json) throw new Error('Stale export: ' + target.pathname);
}
