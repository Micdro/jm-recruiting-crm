import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8080',
});

export async function getCompanies() {
  const response = await api.get('/api/companies');
  return response.data;
}

export async function createCompany(company) {
  const response = await api.post('/api/companies', company);
  return response.data;
}

export async function getContacts() {
  const response = await api.get('/api/contacts');
  return response.data;
}

export async function createContact(contact) {
  const response = await api.post('/api/contacts', contact);
  return response.data;
}