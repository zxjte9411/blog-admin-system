import { describe, expect, it } from 'vitest';
import { getPageNumbers } from './pagination';

describe('getPageNumbers', () => {
  it('returns [0] when totalPages <= 1', () => {
    expect(getPageNumbers(0, 0)).toEqual([0]);
    expect(getPageNumbers(0, 1)).toEqual([0]);
  });

  it('returns all pages when totalPages <= 7', () => {
    expect(getPageNumbers(2, 5)).toEqual([0, 1, 2, 3, 4]);
  });

  it('returns start-focused window with trailing ellipsis when page < 3 in large list', () => {
    expect(getPageNumbers(1, 10)).toEqual([0, 1, 2, 3, -1, 9]);
  });

  it('returns end-focused window with leading ellipsis when near the end', () => {
    expect(getPageNumbers(8, 10)).toEqual([0, -1, 6, 7, 8, 9]);
  });

  it('returns center window with two ellipses when in middle', () => {
    expect(getPageNumbers(5, 10)).toEqual([0, -1, 4, 5, 6, -1, 9]);
  });
});
