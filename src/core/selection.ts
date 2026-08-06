export type RandomSource = () => number;

export function weightedPick<T>(
  list: T[],
  weightOf: (item: T) => number,
  random: RandomSource = Math.random,
  recent?: Set<T>,
  avoid?: T,
): T | undefined {
  const choices = list
    .map((item) => {
      let weight = weightOf(item);
      if (recent?.has(item)) weight = 1;
      if (list.length > 1 && item === avoid) weight = 0;
      return { item, weight };
    })
    .filter(({ weight }) => weight > 0);
  const total = choices.reduce((sum, item) => sum + item.weight, 0);
  let roll = random() * total;
  for (const choice of choices) {
    roll -= choice.weight;
    if (roll < 0) return choice.item;
  }
  return choices.at(-1)?.item ?? list[0];
}

export function editDistance(left: string, right: string): number {
  const row = Array.from({ length: right.length + 1 }, (_, index) => index);
  for (let leftIndex = 1; leftIndex <= left.length; leftIndex += 1) {
    let previous = row[0];
    row[0] = leftIndex;
    for (let rightIndex = 1; rightIndex <= right.length; rightIndex += 1) {
      const old = row[rightIndex];
      row[rightIndex] = Math.min(
        row[rightIndex] + 1,
        row[rightIndex - 1] + 1,
        previous + (left[leftIndex - 1] === right[rightIndex - 1] ? 0 : 1),
      );
      previous = old;
    }
  }
  return row[right.length];
}

export function commonPrefix(left: string, right: string): number {
  let index = 0;
  while (index < left.length && index < right.length && left[index] === right[index]) index += 1;
  return index / Math.max(left.length, right.length, 1);
}

export function zhSimilarity(left: string, right: string): number {
  const tokens = (value: string) => value.split(/[；，、/（）\s]+/).filter((token) => token.length > 1);
  const leftTokens = tokens(left);
  const rightTokens = new Set(tokens(right));
  const exact = leftTokens.filter((token) => rightTokens.has(token)).length;
  const leftCharacters = new Set(left.replace(/[；，、/（）\s]/g, ""));
  const rightCharacters = new Set(right.replace(/[；，、/（）\s]/g, ""));
  const common = [...leftCharacters].filter((character) => rightCharacters.has(character)).length;
  const union = new Set([...leftCharacters, ...rightCharacters]).size || 1;
  return exact * 2 + common / union;
}

export function meaningParts(value: string): string[] {
  return String(value).split("；").map((part) => part.trim()).filter(Boolean);
}

export interface ChoiceWord {
  en: string;
  zh: string;
}

export function makeMeaningChoices<T extends ChoiceWord>(
  words: T[],
  target: T,
  correctMeaning: string,
  random: RandomSource = Math.random,
): string[] {
  const own = new Set(meaningParts(target.zh));
  const unique = [...new Set(words
    .filter((word) => word !== target)
    .flatMap((word) => meaningParts(word.zh))
    .filter((meaning) => !own.has(meaning) && meaning !== correctMeaning))]
    .map((meaning) => ({ meaning, score: zhSimilarity(correctMeaning, meaning) + random() * 0.5 }))
    .sort((left, right) => right.score - left.score);
  const near = unique.slice(0, 12);
  const distractors: string[] = [];
  while (near.length && distractors.length < 3) {
    const index = Math.floor(random() * near.length);
    distractors.push(near.splice(index, 1)[0].meaning);
  }
  for (const candidate of unique) {
    if (distractors.length >= 3) break;
    if (!distractors.includes(candidate.meaning)) distractors.push(candidate.meaning);
  }
  return shuffle([correctMeaning, ...distractors.slice(0, 3)], random);
}

export function distractorScore(
  target: ChoiceWord,
  other: ChoiceWord,
  random: RandomSource = Math.random,
): number {
  const maxLength = Math.max(target.en.length, other.en.length, 1);
  const spelling = 1 - editDistance(target.en, other.en) / maxLength;
  return spelling * 4 + commonPrefix(target.en, other.en) * 1.5 + zhSimilarity(target.zh, other.zh) * 2 + random() * 0.25;
}

export function makeWordChoices<T extends ChoiceWord>(
  words: T[],
  target: T,
  random: RandomSource = Math.random,
): T[] {
  const top = words
    .filter((word) => word !== target)
    .map((word) => ({ word, score: distractorScore(target, word, random) }))
    .sort((left, right) => right.score - left.score)
    .slice(0, 10);
  const chosen: T[] = [];
  for (const candidate of top) {
    if (chosen.length >= 3) break;
    const tooSimilar = chosen.some((other) => {
      const maxLength = Math.max(other.en.length, candidate.word.en.length, 1);
      return other.en.length > 2 && candidate.word.en.length > 2 &&
        editDistance(other.en, candidate.word.en) / maxLength < 0.34;
    });
    if (!tooSimilar) chosen.push(candidate.word);
  }
  for (const candidate of top) {
    if (chosen.length >= 3) break;
    if (!chosen.includes(candidate.word)) chosen.push(candidate.word);
  }
  return shuffle([target, ...chosen.slice(0, 3)], random);
}

function shuffle<T>(items: T[], random: RandomSource): T[] {
  for (let index = items.length - 1; index > 0; index -= 1) {
    const other = Math.floor(random() * (index + 1));
    [items[index], items[other]] = [items[other], items[index]];
  }
  return items;
}
