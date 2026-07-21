const nl = document.getElementById('nl');
const suggestBtn = document.getElementById('suggest');
const runBtn = document.getElementById('run');
const clearBtn = document.getElementById('clear');
const sqlEl = document.getElementById('sql');
const resultsEl = document.getElementById('results');

function replaceResults(nodeOrText) {
  resultsEl.replaceChildren();
  if (typeof nodeOrText === 'string') {
    resultsEl.textContent = nodeOrText;
    return;
  }
  resultsEl.appendChild(nodeOrText);
}

function renderPre(value) {
  const pre = document.createElement('pre');
  pre.textContent = value;
  return pre;
}

function renderTable(rows) {
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
      td.textContent = row[col] === null ? '' : String(row[col]);
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  return table;
}

suggestBtn.addEventListener('click', async () => {
  const text = nl.value.trim();
  if (!text) return alert('Please enter a question.');
  sqlEl.textContent = 'Thinking...';
  runBtn.disabled = true;
  try {
    const res = await fetch('/api/nlp-to-sql', { method: 'POST', headers:{'Content-Type':'text/plain'}, body: text });
    if (!res.ok) throw new Error(await res.text());
    const data = await res.json();
    sqlEl.textContent = data.sql || '(no sql)';
    runBtn.disabled = false;
    replaceResults('');
  } catch (e) {
    sqlEl.textContent = 'Error: ' + e.message;
    runBtn.disabled = true;
  }
});

runBtn.addEventListener('click', async () => {
  const sql = sqlEl.textContent.trim();
  if (!sql || sql.startsWith('Error')) return alert('No valid SQL to run.');
  replaceResults('Running...');
  try {
    const res = await fetch('/api/query', { method: 'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ sql }) });
    const data = await res.json();
    if (data.error) {
      replaceResults(renderPre(JSON.stringify(data, null, 2)));
      return;
    }
    const rows = data.rows || [];
    if (rows.length === 0) { replaceResults('No rows'); return; }
    replaceResults(renderTable(rows));
  } catch (e) {
    replaceResults(renderPre(e.message));
  }
});

clearBtn.addEventListener('click',()=>{ nl.value=''; sqlEl.textContent='(no suggestion yet)'; replaceResults('(no results yet)'); runBtn.disabled=true; });

// Quick sample suggestions
nl.value = 'List users with their email addresses';
