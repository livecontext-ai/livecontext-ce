// @vitest-environment jsdom
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PartnerEarningsArea } from '../PartnerEarningsArea';

const money = (major: number) => `$${major.toLocaleString('en', { maximumFractionDigits: 0 })}`;

/** The money axis labels, bottom to top (each sits in its grid line's group; the month labels do not). */
function axis(values: number[]): string[] {
  const { container } = render(
    <PartnerEarningsArea values={values} money={money} labels={['a', 'b', 'c']} label="chart" idPrefix="t" />,
  );
  return [...container.querySelectorAll('g > text')].map((n) => n.textContent ?? '');
}

describe('the earnings chart axis', () => {
  afterEach(cleanup);

  it('reads against a round top above the peak', () => {
    expect(axis([0, 120, 480])).toEqual(['$0', '$250', '$500']);
  });

  it('regression: a year with nothing earned still gets three distinct labels, not "$1" twice', () => {
    expect(axis([0, 0, 0])).toEqual(['$0', '$50', '$100']);
  });

  it('a tiny year is not drawn against a top its whole-amount labels cannot tell apart', () => {
    expect(axis([0, 0.3])).toEqual(['$0', '$5', '$10']);
  });

  it('regression: with two months the middle label is the first one, drawn once and not on top of itself', () => {
    const { container } = render(
      <PartnerEarningsArea values={[1, 2]} money={money} labels={['Sep 26', 'Sep 26', 'Oct 26']} label="chart" idPrefix="t" />,
    );
    const months = [...container.querySelectorAll('svg > text')].map((n) => n.textContent);
    expect(months).toEqual(['Sep 26', 'Oct 26']);
  });

  it('fewer than two months draws nothing', () => {
    const { container } = render(
      <PartnerEarningsArea values={[5]} money={money} labels={['a', 'b', 'c']} label="chart" idPrefix="t" />,
    );
    expect(container.querySelector('svg')).toBeNull();
  });
});
