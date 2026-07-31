import { redirect } from 'next/navigation';

/** Keep existing bookmarks working with the single palm capture flow. */
export default function MeasurePage() {
  redirect('/palm');
}
