import { formatWeekday } from '../format.js';
import { LATER_OPTIONS, laterDate } from '../later.js';
import ChoiceMenu from './ChoiceMenu.jsx';

/**
 * "Later" on a Today row: moves the follow-up to the next business day or a week out without
 * opening the record. Each choice shows the day it lands on.
 */
export default function LaterMenu({ name, today, disabled = false, onPick }) {
  const choices = LATER_OPTIONS.map(([until, label]) => {
    const day = formatWeekday(laterDate(until, today));
    return { value: until, label, detail: day, ariaLabel: `${label}, ${day}` };
  });

  return (
    <ChoiceMenu
      label="Later"
      buttonLabel={`Move ${name} to later`}
      groupLabel={`Move ${name} to`}
      choices={choices}
      disabled={disabled}
      onPick={onPick}
    />
  );
}
