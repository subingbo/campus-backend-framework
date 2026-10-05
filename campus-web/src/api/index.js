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
