import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080',
});

export async function getCompanies() {
  const response = await api.get('/api/companies');
  return response.data;
}