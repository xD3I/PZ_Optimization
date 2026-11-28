// The C++ original (binary-greedy-meshing v2 src/mesher.h, MIT) on the blocks GreedyMesherBench writes.
// g++ -O3 -march=native -I<binary-greedy-meshing>/src bgm_bench.cpp -o bgm_bench && ./bgm_bench <dir>
#define BM_IMPLEMENTATION
#include "mesher.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>
int main(int argc, char** argv) {
  std::string dir = argc > 1 ? argv[1] : ".";
  const char* names[] = {"terrain-a", "terrain-b", "terrain-c", "random-1/3", "checker"};
  for (int i = 0; i < 5; i++) {
    std::vector<uint8_t> v(CS_P3); std::vector<uint64_t> mask(CS_P2);
    FILE* f = fopen((dir + "/block-" + std::to_string(i) + ".bin").c_str(), "rb");
    if (!f) return 1;
    if (fread(v.data(), 1, CS_P3, f) != (size_t)CS_P3 || fread(mask.data(), 8, CS_P2, f) != (size_t)CS_P2) return 2;
    fclose(f);
    MeshData md;
    md.faceMasks = new uint64_t[CS_2 * 6]; md.opaqueMask = mask.data();
    md.forwardMerged = new uint8_t[CS_2](); md.rightMerged = new uint8_t[CS]();
    md.vertices = new std::vector<uint64_t>(); md.vertices->resize(CS_P3); md.maxVertices = CS_P3;
    for (int w = 0; w < 3000; w++) mesh(v.data(), md);
    std::vector<double> t(2000);
    for (auto& x : t) { auto a = std::chrono::steady_clock::now(); mesh(v.data(), md); x = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - a).count(); }
    std::sort(t.begin(), t.end());
    printf("c++  %-11s median %7.1f us  p90 %7.1f us  quads %6d\n", names[i], t[t.size() / 2], t[t.size() * 9 / 10], md.vertexCount - 1);
  }
}
