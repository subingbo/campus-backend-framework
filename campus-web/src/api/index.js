import http from './http'

export const authApi = {
  login: (payload) => http.post('/auth/login', payload),
  register: (payload) => http.post('/auth/register', payload)
}

export const mapApi = {
  list: () => http.get('/maps'),
  detail: (id) => http.get(`/maps/${id}`),
  createGroup: (payload) => http.post('/groups', payload),
  joinGroup: (payload) => http.post('/groups/join', payload),
  listPlaces: (id) => http.get(`/maps/${id}/places`),
  addPlace: (id, payload) => http.post(`/maps/${id}/places`, payload),
  sharePlace: (id, placeId, payload = {}) => http.post(`/maps/${id}/places/${placeId}`, payload),
  removePlace: (id, placeId) => http.delete(`/maps/${id}/places/${placeId}`),
  updateMemberRole: (id, userId, payload) => http.put(`/maps/${id}/members/${userId}`, payload),
  removeMember: (id, userId) => http.delete(`/maps/${id}/members/${userId}`),
  listComments: (id, placeId) => http.get(`/maps/${id}/places/${placeId}/comments`),
  addComment: (id, placeId, payload) => http.post(`/maps/${id}/places/${placeId}/comments`, payload)
}

export const placeApi = {
  categories: () => http.get('/place-categories'),
  update: (id, payload) => http.put(`/places/${id}`, payload)
}

export const friendApi = {
  list: () => http.get('/friends'),
  requests: () => http.get('/friends/requests'),
  search: (keyword) => http.get('/friends/search', { params: { keyword } }),
  sendRequest: (payload) => http.post('/friends/requests', payload),
  accept: (id) => http.post(`/friends/requests/${id}/accept`),
  reject: (id) => http.post(`/friends/requests/${id}/reject`),
  remove: (userId) => http.delete(`/friends/${userId}`)
}

export const chatApi = {
  conversations: () => http.get('/conversations'),
  createPrivate: (userId) => http.post('/conversations/private', { userId }),
  forMap: (mapId) => http.get(`/conversations/maps/${mapId}`),
  forPlace: (placeId) => http.get(`/conversations/places/${placeId}`),
  messages: (id, beforeId = null, limit = 50) =>
    http.get(`/conversations/${id}/messages`, { params: { beforeId, limit } })
}
