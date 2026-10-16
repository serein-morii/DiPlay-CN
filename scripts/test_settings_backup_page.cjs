// Offline browser-flow regression checks. Run: node scripts/test_settings_backup_page.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../common/src/main/assets/settings-backup.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

async function run(language, { accept = true, invalid = false, large = false, readError = false, conflict = false } = {}) {
  const elements = new Map();
  const calls = [];
  const prompts = [];
  const document = {
    documentElement: {},
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, { textContent: '', className: '', disabled: false,
        addEventListener(event, action) { this[event] = action; }, click() {} });
      return elements.get(id);
    }
  };
  const sandbox = {
    document, navigator: { language }, window: { confirm(text) { prompts.push(text); return accept; } },
    FileReader: class {
      readAsText(file) { this.result = file.contents; if (readError) this.onerror(); else this.onload(); }
    },
    fetch: async (route, request) => {
      calls.push([route, request]);
      return { ok: !(route === 'import' && conflict), json: async () =>
        ({ applied: 3, skipped: 2, invalid, confirmation: 'preview-digest' }) };
    }
  };
  vm.runInNewContext(script, sandbox);
  const file = elements.get('file');
  file.files = [{ contents: '{"settings":"test"}', size: large ? 512 * 1024 + 1 : 19 }];
  file.change();
  await new Promise(setImmediate);
  await new Promise(setImmediate);
  return { elements, calls, prompts, document };
}

(async () => {
  let checks = 0;
  for (const language of ['en', 'zh-CN', 'zh-TW', 'es', 'ru', 'uk', 'ar', 'ja']) {
    const yes = await run(language);
    assert.deepEqual(yes.calls.map(c => c[0]), ['preview', 'import']);
    assert.equal(yes.prompts.length, 1);
    assert.ok(yes.prompts[0].includes('3') && yes.prompts[0].includes('2'));
    assert.equal(yes.calls[0][1].body, yes.calls[1][1].body);
    assert.equal(yes.calls[1][1].headers['X-DiPlay-Preview'], 'preview-digest');
    assert.equal(yes.elements.get('importBtn').disabled, false);
    assert.equal(yes.document.documentElement.dir, language === 'ar' ? 'rtl' : 'ltr');
    const no = await run(language, { accept: false });
    assert.deepEqual(no.calls.map(c => c[0]), ['preview']);
    assert.equal(no.elements.get('importBtn').disabled, false);
    checks += 2;
  }
  for (const options of [{ invalid: true }, { large: true }, { readError: true }, { conflict: true }]) {
    const result = await run('en', options);
    assert.equal(result.elements.get('result').className, 'err');
    assert.equal(result.elements.get('importBtn').disabled, false);
    if (!options.conflict) assert.ok(!result.calls.some(c => c[0] === 'import'));
    checks++;
  }
  process.stdout.write(`PASS: ${checks} backup-page flows (8 locales, confirm/cancel, errors)\n`);
})().catch(error => { console.error(error); process.exitCode = 1; });
