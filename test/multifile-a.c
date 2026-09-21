static int helper_a(int x) { return x + 1; }
int shared_var = 100;
int compute(int x) { return helper_a(x) + shared_var; }
