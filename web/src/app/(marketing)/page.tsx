export default function Home() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-1 flex-col justify-center gap-4 px-4 py-24">
      <h1 className="text-4xl font-semibold tracking-tight">Crypto Payment</h1>
      <p className="text-lg text-neutral-600">
        Accept stablecoin payments with one API call. Payment intents, a hosted checkout, signed webhooks and payouts to
        your own wallet.
      </p>
      <p className="text-sm text-neutral-500">Work in progress. Runs on testnets only.</p>
    </main>
  );
}
