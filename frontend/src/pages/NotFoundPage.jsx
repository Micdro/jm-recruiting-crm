import { Link } from 'react-router';
import { Empty } from '../components/Bits.jsx';
import { useTitle } from '../hooks.js';

export default function NotFoundPage() {
  useTitle('Not found');
  return (
    <Empty title="That page doesn’t exist">
      <Link to="/">Go to Today</Link>
    </Empty>
  );
}
