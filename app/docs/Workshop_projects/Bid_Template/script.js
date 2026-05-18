// script.js – all app logic (offline, no external APIs)
let rooms = [];
let rates = {
  pan: 50,
  glass: 25,
  floor: 10,
  niche: 500,
  bench: 500,
  wall: 10
};

function updateRates() {
  rates.pan = parseFloat(document.getElementById('rate-pan').value) || 50;
  rates.glass = parseFloat(document.getElementById('rate-glass').value) || 25;
  rates.floor = parseFloat(document.getElementById('rate-floor').value) || 10;
  rates.niche = parseFloat(document.getElementById('rate-niche').value) || 500;
  rates.bench = parseFloat(document.getElementById('rate-bench').value) || 500;
  rates.wall = parseFloat(document.getElementById('wall-tier').value) || 10;
  calculateAll();
}

function createRoomCard(roomId) {
  const div = document.createElement('div');
  div.className = 'room-card';
  div.innerHTML = `
    <div class="room-header">
      <input type="text" placeholder="Room Code (A-1)" value="A-${rooms.length + 1}">
      <button class="remove-btn">Remove</button>
    </div>
    <select class="surface-type">
      <option value="pan">Shower Pan</option>
      <option value="walls">Shower Walls / Tub Surround</option>
      <option value="floor">Bath Floor</option>
      <option value="niche">Niche</option>
      <option value="bench">Bench / Shelf</option>
      <option value="glass">Glass Tile Upcharge</option>
    </select>
    <div class="dim-fields"></div>
    <label style="margin-top:8px;display:block;">
      <input type="checkbox" class="overage-toggle" checked> 5% overage
    </label>
    <div class="calc-display">
      <div>Gross: <span class="gross">0</span> sq ft</div>
      <div>Net billable: <span class="net">0</span> sq ft</div>
      <div>Subtotal: <strong class="subtotal">$0</strong></div>
    </div>
  `;

  const removeBtn = div.querySelector('.remove-btn');
  removeBtn.onclick = () => {
    rooms = rooms.filter(r => r.id !== roomId);
    div.remove();
    calculateAll();
  };

  const select = div.querySelector('.surface-type');
  const dimContainer = div.querySelector('.dim-fields');
  const overage = div.querySelector('.overage-toggle');

  function attachInputListeners() {
    div.querySelectorAll('input[type="number"], input[type="text"]').forEach(inp => {
      inp.oninput = calculateAll;
    });
  }

  function renderDims() {
    dimContainer.innerHTML = '';
    const type = select.value;
    if (type === 'pan' || type === 'floor') {
      dimContainer.innerHTML = `
        <div style="display:flex;gap:8px;">
          <input type="number" placeholder="Length (ft)" class="len" step="0.1">
          <input type="number" placeholder="Width (ft)" class="wid" step="0.1">
        </div>`;
    } else if (type === 'walls') {
      dimContainer.innerHTML = `
        <div style="display:flex;gap:8px;">
          <input type="number" placeholder="Height (ft)" class="hgt" value="9" step="0.1">
          <input type="number" placeholder="Perimeter (ft)" class="perim" step="0.1">
        </div>`;
    } else {
      dimContainer.innerHTML = `<input type="number" placeholder="Sq ft or qty" class="direct" step="0.1">`;
    }
    attachInputListeners();
  }

  select.onchange = () => { renderDims(); calculateAll(); };
  overage.onchange = calculateAll;

  renderDims();
  attachInputListeners(); // initial attach

  return { div, id: roomId, getData: () => {
    const type = select.value;
    const over = overage.checked;
    let gross = 0;
    if (type === 'pan' || type === 'floor') {
      const l = parseFloat(div.querySelector('.len')?.value) || 0;
      const w = parseFloat(div.querySelector('.wid')?.value) || 0;
      gross = l * w;
    } else if (type === 'walls') {
      const h = parseFloat(div.querySelector('.hgt')?.value) || 0;
      const p = parseFloat(div.querySelector('.perim')?.value) || 0;
      gross = h * p;
    } else {
      gross = parseFloat(div.querySelector('.direct')?.value) || 0;
    }
    const net = over ? gross * 1.05 : gross;
    let rate = 0;
    let subtotal = 0;
    if (type === 'pan') rate = rates.pan;
    else if (type === 'walls') rate = rates.wall;
    else if (type === 'floor') rate = rates.floor;
    else if (type === 'niche') subtotal = rates.niche;
    else if (type === 'bench') subtotal = rates.bench;
    else if (type === 'glass') rate = rates.glass;

    if (rate > 0) subtotal = rate * net;
    return { gross, net, subtotal, type, over };
  }};
}

function addRoom() {
  const roomId = Date.now();
  const roomObj = createRoomCard(roomId);
  rooms.push(roomObj);
  document.getElementById('roomsContainer').appendChild(roomObj.div);
  calculateAll();
}

function calculateAll() {
  updateRates();
  let total = 0;
  rooms.forEach(room => {
    const data = room.getData();
    const div = room.div;
    div.querySelector('.gross').textContent = data.gross.toFixed(1);
    div.querySelector('.net').textContent = data.net.toFixed(1);
    div.querySelector('.subtotal').textContent = '$' + data.subtotal.toFixed(0);
    total += data.subtotal;
  });

  const contingency = document.getElementById('contingencyToggle').checked;
  if (contingency) total *= 1.05;

  document.getElementById('grandTotal').textContent = '$' + total.toFixed(0);
}

function loadExampleData() {
  document.getElementById('jobName').value = 'Johnson AA Tile Plan';
  alert('Example data ready – add rooms and enter dimensions from README for exact $13,535 total.');
}

function setupListeners() {
  document.getElementById('addRoomBtn').onclick = addRoom;
  document.getElementById('contingencyToggle').onchange = calculateAll;

  document.querySelectorAll('.pricing-grid input, #wall-tier').forEach(el => {
    el.oninput = calculateAll;
  });

  document.getElementById('copyNoteBtn').onclick = () => {
    const text = `Tile Bid – ${document.getElementById('jobName').value}\n` +
      `Date: ${document.getElementById('date').value}\n\n` +
      `Total: ${document.getElementById('grandTotal').textContent}\n` +
      `(Labor only • Materials supplied by company)`;
    navigator.clipboard.writeText(text).then(() => alert('Copied to clipboard'));
  };

  document.getElementById('savePdfBtn').onclick = () => window.print();
  document.getElementById('resetBtn').onclick = () => location.reload();

  const dateInput = document.getElementById('date');
  dateInput.value = new Date().toISOString().split('T')[0];

  window.loadExampleData = loadExampleData;
}

function init() {
  setupListeners();
  addRoom();
  calculateAll();

  const container = document.querySelector('.container');
  if (container) {
    container.addEventListener('touchmove', (e) => {
      e.stopPropagation();
    }, { passive: true });
  }
}

init();