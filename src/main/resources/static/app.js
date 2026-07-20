const nl = document.getElementById('nl');
const suggestBtn = document.getElementById('suggest');
const runBtn = document.getElementById('run');
const clearBtn = document.getElementById('clear');
const sqlEl = document.getElementById('sql');
const resultsEl = document.getElementById('results');

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
    resultsEl.innerHTML = '';
  } catch (e) {
    sqlEl.textContent = 'Error: ' + e.message;
    runBtn.disabled = true;
  }
});

runBtn.addEventListener('click', async () => {
  const sql = sqlEl.textContent.trim();
  if (!sql || sql.startsWith('Error')) return alert('No valid SQL to run.');
  resultsEl.innerHTML = 'Running...';
  try {
    const res = await fetch('/api/query', { method: 'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({ sql }) });
    const data = await res.json();
    if (data.error) {
      resultsEl.innerHTML = '<pre>' + JSON.stringify(data, null, 2) + '</pre>';
      return;
    }
    const rows = data.rows || [];
    if (rows.length === 0) { resultsEl.innerHTML = '<div>No rows</div>'; return; }
    const cols = Object.keys(rows[0]);
    let html = '<table><thead><tr>' + cols.map(c=>'<th>'+c+'</th>').join('') + '</tr></thead><tbody>';
    html += rows.map(r=>'<tr>'+cols.map(c=>'<td>'+String(r[c]===null?'':r[c])+'</td>').join('')+'</tr>').join('');
    html += '</tbody></table>';
    resultsEl.innerHTML = html;
  } catch (e) {
    resultsEl.innerHTML = '<pre>' + e.message + '</pre>';
  }
});

clearBtn.addEventListener('click',()=>{ nl.value=''; sqlEl.textContent='(no suggestion yet)'; resultsEl.innerHTML='(no results yet)'; runBtn.disabled=true; });

// Quick sample suggestions
nl.value = 'List users with their email addresses';
