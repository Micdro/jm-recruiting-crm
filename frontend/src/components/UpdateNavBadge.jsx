import { useUpdateReady } from '../appUpdate.js';

/** "Update ready" next to This computer in the menu while a newer version waits to be installed. */
export default function UpdateNavBadge() {
  return useUpdateReady() ? <span className="nav-badge">Update ready</span> : null;
}
