const nl = document.getElementById('nl');
const suggestBtn = document.getElementById('suggest');
const runBtn = document.getElementById('run');
const clearBtn = document.getElementById('clear');
const sqlEl = document.getElementById('sql');
const resultsEl = document.getElementById('results');

const SUGGEST_LABEL = 'Suggest Query';
const RUN_LABEL = 'Run Query';
const SQL_PLACEHOLDER = '(no suggestion yet)';
const RESULTS_PLACEHOLDER = '(no answer yet)';

function setResults(text, mode = 'placeholder') {
  resultsEl.textContent = text;
  resultsEl.classList.remove('placeholder', 'answer', 'status');
  resultsEl.classList.add(mode);
}

function setButtonLoading(button, loading, loadingLabel, defaultLabel) {
  button.disabled = loading;
  button.textContent = loading ? loadingLabel : defaultLabel;
}

function setSqlPanel(text, mode) {
  sqlEl.textContent = text;
  sqlEl.classList.remove('error', 'sql-output', 'placeholder');
  sqlEl.classList.add(mode);
  sqlEl.dataset.state = mode === 'sql-output' ? 'sql' : mode === 'error' ? 'error' : 'empty';
  runBtn.disabled = mode !== 'sql-output';
}

function formatError(data, fallback) {
  const code = data?.error ? `[${data.error}] ` : '';
  let message = data?.message || fallback || 'Request failed.';

  message = String(message)
    .replace(/<EOL>/g, '\n')
    .replace(/\\n/g, '\n')
    .replace(/\r\n/g, '\n');

  const jsonMatch = message.match(/"message"\s*:\s*"([^"]+)"/);
  if (jsonMatch) {
    message = jsonMatch[1].replace(/\\n/g, '\n');
  }

  return (code + message)
    .replace(/\{[\s\S]*\}/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function hasRunnableSql() {
  return sqlEl.dataset.state === 'sql' && sqlEl.textContent.trim().length > 0;
}

suggestBtn.addEventListener('click', async () => {
  const text = nl.value.trim();
  if (!text) {
    setSqlPanel('Input text cannot be empty', 'error');
    return;
  }

  setSqlPanel('Thinking...', 'placeholder');
  runBtn.disabled = true;
  setButtonLoading(suggestBtn, true, 'Suggesting...', SUGGEST_LABEL);

  try {
    const res = await fetch('/api/nlp-to-sql', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: text });
    const data = await res.json().catch(() => ({}));

    if (!res.ok || data.error) {
      setSqlPanel(formatError(data), 'error');
      setResults(RESULTS_PLACEHOLDER, 'placeholder');
      return;
    }

    setSqlPanel(data.sql || '(no sql returned)', 'sql-output');
    setResults(RESULTS_PLACEHOLDER, 'placeholder');
  } catch (e) {
    setSqlPanel(formatError({}, e.message), 'error');
    setResults(RESULTS_PLACEHOLDER, 'placeholder');
  } finally {
    setButtonLoading(suggestBtn, false, 'Suggesting...', SUGGEST_LABEL);
  }
});

runBtn.addEventListener('click', async () => {
  if (!hasRunnableSql()) {
    setSqlPanel('No valid SQL to run.\nUse Suggest Query first.', 'error');
    return;
  }

  const sql = sqlEl.textContent.trim();
  const question = nl.value.trim();
  setResults('Running query...', 'status');
  setButtonLoading(runBtn, true, 'Running...', RUN_LABEL);
  suggestBtn.disabled = true;

  try {
    const res = await fetch('/api/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sql, question })
    });
    const data = await res.json().catch(() => ({}));

    if (!res.ok || data.error) {
      setSqlPanel(formatError(data), 'error');
      setResults(RESULTS_PLACEHOLDER, 'placeholder');
      return;
    }

    setResults(data.answer || 'No answer was returned.', 'answer');
  } catch (e) {
    setSqlPanel(formatError({}, e.message), 'error');
    setResults(RESULTS_PLACEHOLDER, 'placeholder');
  } finally {
    setButtonLoading(runBtn, false, 'Running...', RUN_LABEL);
    suggestBtn.disabled = false;
    if (sqlEl.dataset.state === 'sql') {
      runBtn.disabled = false;
    }
  }
});

clearBtn.addEventListener('click', () => {
  nl.value = '';
  setSqlPanel(SQL_PLACEHOLDER, 'placeholder');
  setResults(RESULTS_PLACEHOLDER, 'placeholder');
  runBtn.disabled = true;
});

setSqlPanel(SQL_PLACEHOLDER, 'placeholder');
setResults(RESULTS_PLACEHOLDER, 'placeholder');
nl.value = 'List users with their email addresses';
