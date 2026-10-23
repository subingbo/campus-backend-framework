/* 静态样板渲染：只操作 design-lab 页面，不接后端。 */
(function () {
  const data = window.LAB_DATA;
  if (!data) return;

  const icon = (name, cls = '') =>
    `<svg class="icon ${cls}" aria-hidden="true"><use href="./icons.svg#i-${name}"></use></svg>`;

  const esc = (value) =>
    String(value ?? '').replace(/[&<>"']/g, (ch) => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    })[ch]);

  function renderMaps(container) {
    const rows = data.maps
      .map(
        (m) => `
        <button type="button" class="map-row${m.selected ? ' is-selected' : ''}" data-map-id="${m.id}">
          <span class="map-row__top">
            <span class="map-row__name">${esc(m.name)}</span>
            <span class="tag">${esc(m.role)}</span>
          </span>
          <p class="map-row__desc">${esc(m.description)}</p>
          <span class="map-row__stats"><span>${m.members} 位成员</span><span>${m.places} 个地点</span></span>
        </button>`
      )
      .join('');
    container.innerHTML = rows;
  }

  function renderPlaces(container) {
    const rows = data.places
      .map(
        (p) => `
        <button type="button" class="place-row${p.selected ? ' is-selected' : ''}" data-place-id="${p.id}">
          <span class="place-row__body">
            <span class="place-row__name">${esc(p.name)}</span>
            <span class="place-row__addr">${esc(p.address)}</span>
            <span class="place-row__meta"><span class="place-row__cat">${esc(p.category)}</span></span>
          </span>
          <span class="place-row__chev">${icon('chevron-right', 'icon--sm')}</span>
        </button>`
      )
      .join('');
    container.innerHTML = rows;
  }

  function renderMembers(container) {
    const max = Number(container.dataset.max || 3);
    const names = data.members.slice(0, max);
    const rest = data.members.length - names.length;
    container.innerHTML =
      names.map((n) => `<span title="${esc(n)}">${esc(n.slice(0, 1))}</span>`).join('') +
      (rest > 0 ? `<span title="其余成员">+${rest}</span>` : '');
  }

  document.querySelectorAll('[data-render="maps"]').forEach(renderMaps);
  document.querySelectorAll('[data-render="places"]').forEach(renderPlaces);
  document.querySelectorAll('[data-render="members"]').forEach(renderMembers);

  function selectPlace(id) {
    const place = data.places.find((p) => String(p.id) === String(id));
    if (!place) return;

    document.querySelectorAll('.place-row').forEach((row) => {
      row.classList.toggle('is-selected', row.dataset.placeId === String(id));
    });
    document.querySelectorAll('.marker[data-place-id]').forEach((marker) => {
      marker.classList.toggle('is-selected', marker.dataset.placeId === String(id));
    });

    const set = (selector, value) => {
      document.querySelectorAll(selector).forEach((el) => {
        el.textContent = value;
      });
    };
    set('[data-place-name]', place.name);
    set('[data-place-category]', place.category);
    set('[data-place-address]', place.address);
    set('[data-place-desc]', place.description);
    set('[data-place-auth]', '由 林晓 添加 · 10 分钟前更新');
  }

  function selectMap(id) {
    const map = data.maps.find((m) => String(m.id) === String(id));
    if (!map) return;
    document.querySelectorAll('.map-row').forEach((row) => {
      row.classList.toggle('is-selected', row.dataset.mapId === String(id));
    });
    const set = (selector, value) => {
      document.querySelectorAll(selector).forEach((el) => {
        el.textContent = value;
      });
    };
    set('[data-map-name]', map.name);
    set('[data-map-desc]', map.description);
    set('[data-map-members]', `${map.members} 位成员`);
    set('[data-map-places]', `${map.places} 个地点`);
  }

  document.addEventListener('click', (event) => {
    const placeRow = event.target.closest('.place-row');
    if (placeRow) {
      selectPlace(placeRow.dataset.placeId);
      return;
    }
    const mapRow = event.target.closest('.map-row');
    if (mapRow) selectMap(mapRow.dataset.mapId);
  });

  document.querySelectorAll('.place-search').forEach((input) => {
    input.addEventListener('input', () => {
      const q = input.value.trim().toLowerCase();
      document.querySelectorAll('.place-row').forEach((row) => {
        const place = data.places.find((p) => String(p.id) === row.dataset.placeId);
        const hay = place ? `${place.name} ${place.address} ${place.category}`.toLowerCase() : '';
        row.hidden = !!q && !hay.includes(q);
      });
    });
  });

  document.querySelectorAll('.chip[data-category]').forEach((chip) => {
    chip.addEventListener('click', () => {
      const category = chip.dataset.category;
      document.querySelectorAll('.chip[data-category]').forEach((c) => {
        c.classList.toggle('is-active', c === chip);
      });
      document.querySelectorAll('.place-row').forEach((row) => {
        const place = data.places.find((p) => String(p.id) === row.dataset.placeId);
        row.hidden = !!category && place?.category !== category;
      });
    });
  });
})();
