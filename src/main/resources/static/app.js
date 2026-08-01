const nl = document.getElementById('nl');
const suggestBtn = document.getElementById('suggest');
const runBtn = document.getElementById('run');
const clearBtn = document.getElementById('clear');
const questionsEl = document.getElementById('questions');
const resultsEl = document.getElementById('results');

const SUGGEST_LABEL = '✨ Suggest Questions';
const RUN_LABEL = '▶ Run Query';
const SUGGESTING_LABEL = '⏳ Suggesting...';
const RUNNING_LABEL = '⏳ Running...';
const QUESTIONS_PLACEHOLDER = '(no questions yet)';
const RESULTS_PLACEHOLDER = '(no results yet)';

function setButtonLoading(button, loading, loadingLabel, defaultLabel) {
  button.disabled = loading;
  button.textContent = loading ? loadingLabel : defaultLabel;
}

function setQuestionsPanel(text, mode, originalInput = null) {
  questionsEl.textContent = text;
  questionsEl.classList.remove('error', 'sql-output', 'placeholder');
  questionsEl.classList.add(mode);
  questionsEl.dataset.state = mode === 'sql-output' ? 'sql' : mode === 'error' ? 'error' : 'empty';
  if (originalInput) {
    questionsEl.dataset.originalInput = originalInput;
  }
  runBtn.disabled = mode !== 'sql-output' && !nl.value.trim();
}

function setResultsMessage(text, mode = 'placeholder') {
  resultsEl.replaceChildren();
  resultsEl.textContent = text;
  resultsEl.className = mode;
}

function renderResultsTable(rows) {
  resultsEl.replaceChildren();
  resultsEl.className = 'has-table';

  const count = document.createElement('p');
  count.className = 'row-count';
  count.textContent = rows.length === 1 ? '1 row returned' : `${rows.length} rows returned`;
  resultsEl.appendChild(count);

  const cols = Object.keys(rows[0]);
  const table = document.createElement('table');
  const thead = document.createElement('thead');
  const headerRow = document.createElement('tr');

  cols.forEach((col) => {
    const th = document.createElement('th');
    th.textContent = col;
    headerRow.appendChild(th);
  });

  thead.appendChild(headerRow);
  table.appendChild(thead);

  const tbody = document.createElement('tbody');
  rows.forEach((row) => {
    const tr = document.createElement('tr');
    cols.forEach((col) => {
      const td = document.createElement('td');
      td.textContent = row[col] === null || row[col] === undefined ? '' : String(row[col]);
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });

  table.appendChild(tbody);
  resultsEl.appendChild(table);
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
  return questionsEl.dataset.state === 'sql' && questionsEl.textContent.trim().length > 0;
}

suggestBtn.addEventListener('click', async () => {
  const text = nl.value.trim();
  if (!text) {
    setQuestionsPanel('Input text cannot be empty', 'error');
    return;
  }

  setQuestionsPanel('Thinking...', 'placeholder');
  runBtn.disabled = true;
  setButtonLoading(suggestBtn, true, SUGGESTING_LABEL, SUGGEST_LABEL);

  try {
    const res = await fetch('/api/nlp-to-sql', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: text });
    const data = await res.json().catch(() => ({}));

    if (!res.ok || data.error) {
      setQuestionsPanel(formatError(data), 'error');
      setResultsMessage(RESULTS_PLACEHOLDER, 'placeholder');
      return;
    }

    const questions = data.questions || data.sql || '(no questions returned)';
    setQuestionsPanel(questions, 'sql-output', text);
    setResultsMessage(RESULTS_PLACEHOLDER, 'placeholder');
  } catch (e) {
    setQuestionsPanel(formatError({}, e.message), 'error');
    setResultsMessage(RESULTS_PLACEHOLDER, 'placeholder');
  } finally {
    setButtonLoading(suggestBtn, false, SUGGESTING_LABEL, SUGGEST_LABEL);
  }
});

runBtn.addEventListener('click', async () => {
  const question = questionsEl.dataset.originalInput || nl.value.trim();
  if (!question) {
    setQuestionsPanel('Please enter a question first.', 'error');
    return;
  }
  setResultsMessage('Converting question to SQL and running query...', 'status');
  setButtonLoading(runBtn, true, RUNNING_LABEL, RUN_LABEL);
  suggestBtn.disabled = true;

  try {
    const res = await fetch('/api/nlp-to-sql', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: question });
    const sqlData = await res.json().catch(() => ({}));
    
    if (!res.ok || sqlData.error) {
      setQuestionsPanel(formatError(sqlData), 'error');
      setResultsMessage(RESULTS_PLACEHOLDER, 'placeholder');
      return;
    }
    
    const sql = sqlData.sql;
    const queryRes = await fetch('/api/query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sql, question })
    });
    const data = await queryRes.json().catch(() => ({}));

    if (!queryRes.ok || data.error) {
      setQuestionsPanel(formatError(data), 'error');
      setResultsMessage(RESULTS_PLACEHOLDER, 'placeholder');
      return;
    }

    const rows = data.rows || [];
    if (rows.length === 0) {
      setResultsMessage('No rows matched your query.', 'placeholder');
      return;
    }

    renderResultsTable(rows);
  } catch (e) {
    setQuestionsPanel(formatError({}, e.message), 'error');
    setResultsMessage(RESULTS_PLACEHOLDER, 'placeholder');
  } finally {
    setButtonLoading(runBtn, false, RUNNING_LABEL, RUN_LABEL);
    suggestBtn.disabled = false;
    if (questionsEl.dataset.state === 'sql') {
      runBtn.disabled = false;
    }
  }
});

clearBtn.addEventListener('click', () => {
  nl.value = '';
  setQuestionsPanel(QUESTIONS_PLACEHOLDER, 'placeholder');
  setResultsMessage(RESULTS_PLACEHOLDER, 'placeholder');
  delete questionsEl.dataset.originalInput;
  runBtn.disabled = true;
});

setQuestionsPanel(QUESTIONS_PLACEHOLDER, 'placeholder');
setResultsMessage(RESULTS_PLACEHOLDER, 'placeholder');
nl.value = 'List users with their email addresses';
runBtn.disabled = false;
